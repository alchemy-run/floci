package io.floci.aws.services.route53resolver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DNS Firewall rule groups and their rules. A rule group is created empty, collects rules that each
 * name one of the account's domain lists, and cannot be deleted while something still uses it: a
 * {@link FirewallRuleGroupUsers} implementation (a Route 53 Profile it is attached to) reports the use.
 */
@ApplicationScoped
public class Route53ResolverFirewallRuleGroups {

    private static final String VALIDATION = "ValidationException";
    private static final List<String> ACTIONS = List.of("ALLOW", "BLOCK", "ALERT");
    private static final List<String> BLOCK_RESPONSES = List.of("NODATA", "NXDOMAIN", "OVERRIDE");

    /** Something that can hold a rule group in use, so that deleting it is a conflict. */
    public interface FirewallRuleGroupUsers {
        /** A description of what uses the rule group with this ARN, if anything does. */
        Optional<String> userOf(String ruleGroupArn);
    }

    private final StorageBackend<String, ObjectNode> groups;
    private final Route53ResolverService resolver;
    private final ObjectMapper mapper;
    private final Instance<FirewallRuleGroupUsers> users;

    @Inject
    public Route53ResolverFirewallRuleGroups(StorageFactory storageFactory, Route53ResolverService resolver,
                                             ObjectMapper mapper, Instance<FirewallRuleGroupUsers> users) {
        this.groups = storageFactory.create("route53resolver", "route53resolver-firewall-rule-groups.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.resolver = resolver;
        this.mapper = mapper;
        this.users = users;
    }

    StorageBackend<String, ObjectNode> store() {
        return groups;
    }

    /** The rule group with this ARN, if the account has one in the ARN's region. */
    public Optional<ObjectNode> byArn(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "route53resolver")) {
            return Optional.empty();
        }
        String resource = AwsArnUtils.parse(arn).resource();
        if (!resource.startsWith("firewall-rule-group/")) {
            return Optional.empty();
        }
        return groups.get(resource.substring("firewall-rule-group/".length()))
                .filter(group -> arn.equals(group.path("Arn").asText())).map(this::view);
    }

    public synchronized ObjectNode create(JsonNode request, String region, String accountId) {
        String name = required(request, "Name");
        String token = required(request, "CreatorRequestId");
        for (ObjectNode existing : groups.scan(key -> true)) {
            if (token.equals(existing.path("CreatorRequestId").asText())
                    && region.equals(AwsArnUtils.parse(existing.path("Arn").asText()).region())) {
                return view(existing);
            }
        }
        String id = "rslvr-frg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String now = Instant.now().toString();
        ObjectNode group = mapper.createObjectNode();
        group.put("Id", id)
                .put("Arn", AwsArnUtils.Arn.of("route53resolver", region, accountId, "firewall-rule-group/" + id)
                        .toString())
                .put("Name", name).put("RuleCount", 0).put("Status", "COMPLETE")
                .put("StatusMessage", "Created Firewall Rule Group").put("OwnerId", accountId)
                .put("CreatorRequestId", token).put("ShareStatus", "NOT_SHARED")
                .put("CreationTime", now).put("ModificationTime", now);
        ObjectNode tags = group.putObject("_Tags");
        for (JsonNode tag : request.path("Tags")) {
            tags.put(tag.path("Key").asText(), tag.path("Value").asText());
        }
        group.putArray("_Rules");
        groups.put(id, group);
        return view(group);
    }

    public ObjectNode get(JsonNode request, String region) {
        return view(require(request.path("FirewallRuleGroupId").asText(null), region));
    }

    public ObjectNode list(JsonNode request, String region) {
        List<ObjectNode> metadata = new ArrayList<>();
        for (ObjectNode group : groups.scan(key -> true)) {
            if (region.equals(AwsArnUtils.parse(group.path("Arn").asText()).region())) {
                ObjectNode item = mapper.createObjectNode();
                for (String field : List.of("Id", "Arn", "Name", "OwnerId", "CreatorRequestId", "ShareStatus")) {
                    item.set(field, group.get(field));
                }
                metadata.add(item);
            }
        }
        metadata.sort(Comparator.comparing(item -> item.path("Id").asText()));
        return page(metadata, request, "FirewallRuleGroups");
    }

    public synchronized ObjectNode delete(JsonNode request, String region) {
        ObjectNode group = require(request.path("FirewallRuleGroupId").asText(null), region);
        for (FirewallRuleGroupUsers user : users) {
            Optional<String> use = user.userOf(group.path("Arn").asText());
            if (use.isPresent()) {
                throw new AwsException("ConflictException", "The rule group " + group.path("Id").asText()
                        + " can't be deleted because it's in use by " + use.get() + ".", 400);
            }
        }
        groups.delete(group.path("Id").asText());
        ObjectNode view = view(group);
        view.put("Status", "DELETING").put("StatusMessage", "Deleting Firewall Rule Group");
        return view;
    }

    // ---------- rules ----------

    public synchronized ObjectNode createRule(JsonNode request, String region) {
        ObjectNode group = require(request.path("FirewallRuleGroupId").asText(null), region).deepCopy();
        String name = required(request, "Name");
        String token = required(request, "CreatorRequestId");
        ArrayNode rules = (ArrayNode) group.get("_Rules");
        for (JsonNode rule : rules) {
            if (token.equals(rule.path("CreatorRequestId").asText())) {
                return rule.deepCopy();
            }
        }
        if (!request.path("Priority").isIntegralNumber()) {
            throw new AwsException(VALIDATION, "Priority is required.", 400);
        }
        int priority = request.path("Priority").asInt();
        String action = required(request, "Action");
        oneOf(action, "Action", ACTIONS);
        String listId = request.path("FirewallDomainListId").asText(null);
        if (listId == null || listId.isBlank()) {
            throw new AwsException(VALIDATION, "FirewallDomainListId is required.", 400);
        }
        requireDomainList(listId, region);
        for (JsonNode rule : rules) {
            if (rule.path("Priority").asInt() == priority) {
                throw new AwsException(VALIDATION, "A rule with priority " + priority
                        + " already exists in the rule group.", 400);
            }
            if (listId.equals(rule.path("FirewallDomainListId").asText())) {
                throw new AwsException(VALIDATION, "The domain list " + listId
                        + " is already used by a rule in the rule group.", 400);
            }
        }
        String now = Instant.now().toString();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("FirewallRuleGroupId", group.path("Id").asText()).put("FirewallDomainListId", listId)
                .put("Name", name).put("Priority", priority).put("Action", action)
                .put("CreatorRequestId", token).put("CreationTime", now).put("ModificationTime", now);
        applyBlock(rule, request);
        rules.add(rule);
        group.put("RuleCount", rules.size()).put("ModificationTime", now);
        groups.put(group.path("Id").asText(), group);
        return rule.deepCopy();
    }

    public synchronized ObjectNode updateRule(JsonNode request, String region) {
        ObjectNode group = require(request.path("FirewallRuleGroupId").asText(null), region).deepCopy();
        ObjectNode rule = ruleFor(group, request);
        if (request.has("Priority")) {
            int priority = request.path("Priority").asInt();
            for (JsonNode other : group.get("_Rules")) {
                if (other != rule && other.path("Priority").asInt() == priority) {
                    throw new AwsException("ConflictException", "A rule with priority " + priority
                            + " already exists in the rule group.", 400);
                }
            }
            rule.put("Priority", priority);
        }
        if (request.hasNonNull("Action")) {
            oneOf(request.path("Action").asText(), "Action", ACTIONS);
            rule.put("Action", request.path("Action").asText());
        }
        if (request.hasNonNull("Name")) {
            rule.put("Name", request.path("Name").asText());
        }
        applyBlock(rule, request);
        rule.put("ModificationTime", Instant.now().toString());
        groups.put(group.path("Id").asText(), group);
        return rule.deepCopy();
    }

    public synchronized ObjectNode deleteRule(JsonNode request, String region) {
        ObjectNode group = require(request.path("FirewallRuleGroupId").asText(null), region).deepCopy();
        ObjectNode rule = ruleFor(group, request);
        ArrayNode rules = (ArrayNode) group.get("_Rules");
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i) == rule) {
                rules.remove(i);
                break;
            }
        }
        group.put("RuleCount", rules.size()).put("ModificationTime", Instant.now().toString());
        groups.put(group.path("Id").asText(), group);
        return rule.deepCopy();
    }

    public ObjectNode listRules(JsonNode request, String region) {
        ObjectNode group = require(request.path("FirewallRuleGroupId").asText(null), region);
        List<ObjectNode> rules = new ArrayList<>();
        for (JsonNode rule : group.get("_Rules")) {
            if (request.has("Priority") && request.path("Priority").asInt() != rule.path("Priority").asInt()) {
                continue;
            }
            if (request.hasNonNull("Action") && !request.path("Action").asText().equals(rule.path("Action").asText())) {
                continue;
            }
            rules.add(((ObjectNode) rule).deepCopy());
        }
        rules.sort(Comparator.comparingInt(rule -> rule.path("Priority").asInt()));
        return page(rules, request, "FirewallRules");
    }

    private void applyBlock(ObjectNode rule, JsonNode request) {
        if (request.hasNonNull("BlockResponse")) {
            oneOf(request.path("BlockResponse").asText(), "BlockResponse", BLOCK_RESPONSES);
        }
        for (String field : List.of("BlockResponse", "BlockOverrideDomain", "BlockOverrideDnsType",
                "BlockOverrideTtl", "FirewallDomainRedirectionAction", "Qtype")) {
            if (request.hasNonNull(field)) {
                rule.set(field, request.get(field).deepCopy());
            }
        }
        if ("BLOCK".equals(rule.path("Action").asText()) && !rule.hasNonNull("BlockResponse")) {
            throw new AwsException(VALIDATION, "BlockResponse is required when Action is BLOCK.", 400);
        }
        if ("OVERRIDE".equals(rule.path("BlockResponse").asText())
                && (!rule.hasNonNull("BlockOverrideDomain") || !rule.hasNonNull("BlockOverrideDnsType")
                || !rule.hasNonNull("BlockOverrideTtl"))) {
            throw new AwsException(VALIDATION, "An OVERRIDE block response needs BlockOverrideDomain, "
                    + "BlockOverrideDnsType and BlockOverrideTtl.", 400);
        }
    }

    private ObjectNode ruleFor(ObjectNode group, JsonNode request) {
        String listId = request.path("FirewallDomainListId").asText(null);
        for (JsonNode rule : group.get("_Rules")) {
            if (listId != null && listId.equals(rule.path("FirewallDomainListId").asText())) {
                return (ObjectNode) rule;
            }
        }
        throw new AwsException("ResourceNotFoundException", "No rule for domain list " + listId
                + " in the rule group " + group.path("Id").asText() + ".", 400);
    }

    private void requireDomainList(String listId, String region) {
        if (resolver.getCustomFirewallDomainList(listId)
                .filter(list -> region.equals(AwsArnUtils.parse(list.path("Arn").asText()).region())).isPresent()) {
            return;
        }
        resolver.getFirewallDomainList(region, listId);
    }

    private ObjectNode require(String id, String region) {
        if (id == null || id.isBlank()) {
            throw new AwsException(VALIDATION, "FirewallRuleGroupId is required.", 400);
        }
        return groups.get(id)
                .filter(group -> region.equals(AwsArnUtils.parse(group.path("Arn").asText()).region()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Can't find the rule group " + id + ".", 400));
    }

    private ObjectNode view(ObjectNode group) {
        ObjectNode view = group.deepCopy();
        view.remove("_Tags");
        view.remove("_Rules");
        return view;
    }

    private static String required(JsonNode request, String field) {
        String value = request.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException(VALIDATION, field + " is required.", 400);
        }
        return value;
    }

    private static void oneOf(String value, String field, List<String> allowed) {
        if (!allowed.contains(value)) {
            throw new AwsException(VALIDATION, field + " must be one of " + String.join(", ", allowed) + ".", 400);
        }
    }

    private ObjectNode page(List<ObjectNode> items, JsonNode request, String field) {
        int max = request.path("MaxResults").asInt(100);
        if (max < 1 || max > 100) {
            throw new AwsException(VALIDATION, "MaxResults must be between 1 and 100.", 400);
        }
        int start = 0;
        if (request.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(new String(Base64.getUrlDecoder().decode(request.path("NextToken").asText()),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new AwsException(VALIDATION, "Invalid NextToken.", 400);
            }
        }
        start = Math.max(0, Math.min(start, items.size()));
        int end = Math.min(items.size(), start + max);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        items.subList(start, end).forEach(array::add);
        if (end < items.size()) {
            out.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }
}
