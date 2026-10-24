package io.github.hectorvent.floci.services.networkfirewall;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class NetworkFirewallService {

    private static final int AVAILABILITY_ZONES_PER_REGION = 6;
    private static final Set<String> RULE_GROUP_TYPES =
            Set.of("STATELESS", "STATEFUL", "STATEFUL_DOMAIN");
    private static final Set<String> LOG_TYPES = Set.of("ALERT", "FLOW", "TLS");
    private static final Set<String> LOG_DESTINATION_TYPES =
            Set.of("S3", "CloudWatchLogs", "KinesisDataFirehose");

    private final ObjectMapper objectMapper;
    private final StorageBackend<String, ObjectNode> ruleGroups;
    private final StorageBackend<String, ObjectNode> firewallPolicies;
    private final StorageBackend<String, ObjectNode> firewalls;
    private final StorageBackend<String, ObjectNode> loggingConfigurations;
    private final StorageBackend<String, ObjectNode> flowOperations;
    private final StorageBackend<String, ObjectNode> analysisReports;

    @Inject
    public NetworkFirewallService(ObjectMapper objectMapper, StorageFactory storageFactory) {
        this(objectMapper,
                storageFactory.create("networkfirewall", "network-firewall-rule-groups.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("networkfirewall", "network-firewall-policies.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("networkfirewall", "network-firewall-firewalls.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("networkfirewall", "network-firewall-logging.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("networkfirewall", "network-firewall-flow-operations.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("networkfirewall", "network-firewall-analysis-reports.json",
                        new TypeReference<Map<String, ObjectNode>>() {}));
    }

    NetworkFirewallService(ObjectMapper objectMapper,
                           StorageBackend<String, ObjectNode> ruleGroups,
                           StorageBackend<String, ObjectNode> firewallPolicies,
                           StorageBackend<String, ObjectNode> firewalls,
                           StorageBackend<String, ObjectNode> loggingConfigurations) {
        this(objectMapper, ruleGroups, firewallPolicies, firewalls, loggingConfigurations,
                new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    NetworkFirewallService(ObjectMapper objectMapper,
                           StorageBackend<String, ObjectNode> ruleGroups,
                           StorageBackend<String, ObjectNode> firewallPolicies,
                           StorageBackend<String, ObjectNode> firewalls,
                           StorageBackend<String, ObjectNode> loggingConfigurations,
                           StorageBackend<String, ObjectNode> flowOperations,
                           StorageBackend<String, ObjectNode> analysisReports) {
        this.objectMapper = objectMapper;
        this.ruleGroups = ruleGroups;
        this.firewallPolicies = firewallPolicies;
        this.firewalls = firewalls;
        this.loggingConfigurations = loggingConfigurations;
        this.flowOperations = flowOperations;
        this.analysisReports = analysisReports;
    }

    // ---------------------------------------------------------------- rule groups

    /**
     * Stored shape: {@code {RuleGroup, RuleGroupResponse, UpdateToken}}. A {@code Rules}
     * Suricata string is normalised into {@code RuleGroup.RulesSource.RulesString}, which is
     * where DescribeRuleGroup reports it.
     */
    public synchronized ObjectNode createRuleGroup(JsonNode request, String region, String accountId) {
        requireObject(request);
        String name = requiredText(request, "RuleGroupName");
        String type = requiredText(request, "Type");
        requireEnum(type, RULE_GROUP_TYPES, "Type");
        int capacity = requiredCapacity(request);
        ObjectNode definition = ruleGroupDefinition(request, type);
        ArrayNode tags = validatedTags(request.get("Tags"), false);
        String arn = ruleGroupArn(region, accountId, type, name);
        if (ruleGroups.get(arn).isPresent()) {
            throw new AwsException("InvalidRequestException", "RuleGroup already exists: " + name, 400);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("RuleGroupArn", arn);
        response.put("RuleGroupName", name);
        response.put("RuleGroupId", UUID.randomUUID().toString());
        copyIfPresent(request, response, "Description");
        response.put("Type", type);
        response.put("Capacity", capacity);
        response.put("RuleGroupStatus", "ACTIVE");
        response.set("Tags", tags);
        response.set("EncryptionConfiguration", encryptionConfiguration(request, null));
        copyIfPresent(request, response, "SourceMetadata");
        copyIfPresent(request, response, "SummaryConfiguration");
        response.put("LastModifiedTime", nowEpochSeconds());

        ObjectNode stored = objectMapper.createObjectNode();
        stored.set("RuleGroup", definition);
        stored.set("RuleGroupResponse", response);
        String token = UUID.randomUUID().toString();
        stored.put("UpdateToken", token);
        if (!isDryRun(request)) {
            ruleGroups.put(arn, stored);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("UpdateToken", token);
        result.set("RuleGroupResponse", ruleGroupResponseView(stored));
        return result;
    }

    public synchronized ObjectNode describeRuleGroup(JsonNode request, String region, String accountId) {
        ObjectNode stored = requireRuleGroup(request, region, accountId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("UpdateToken", ensureStoredToken(ruleGroups, stored, ruleGroupArnOf(stored)));
        if (stored.path("RuleGroup").isObject()) {
            result.set("RuleGroup", stored.get("RuleGroup").deepCopy());
        }
        result.set("RuleGroupResponse", ruleGroupResponseView(stored));
        return result;
    }

    /**
     * UpdateRuleGroup modifies the rule group in place (ARN, id, capacity and tags are
     * retained) and requires the caller's UpdateToken to match the current one.
     * Description is part of the replaced definition; the optional configuration blocks
     * are preserved when omitted.
     */
    public synchronized ObjectNode updateRuleGroup(JsonNode request, String region, String accountId) {
        requireObject(request);
        String requestedType = textOrNull(request, "Type");
        if (requestedType != null) {
            requireEnum(requestedType, RULE_GROUP_TYPES, "Type");
        }
        String token = requiredText(request, "UpdateToken");
        ObjectNode stored = requireRuleGroup(request, region, accountId);
        requireMatchingToken(stored, token);
        ObjectNode current = (ObjectNode) stored.get("RuleGroupResponse");
        String type = current.path("Type").asText("STATEFUL");
        ObjectNode definition = ruleGroupDefinition(request, type);

        ObjectNode updated = stored.deepCopy();
        ObjectNode response = (ObjectNode) updated.get("RuleGroupResponse");
        updated.set("RuleGroup", definition);
        replaceOrRemove(request, response, "Description");
        copyIfPresent(request, response, "SummaryConfiguration");
        copyIfPresent(request, response, "SourceMetadata");
        if (request.has("EncryptionConfiguration")) {
            response.set("EncryptionConfiguration",
                    encryptionConfiguration(request, response.get("EncryptionConfiguration")));
        }
        response.put("LastModifiedTime", nowEpochSeconds());
        ObjectNode result = objectMapper.createObjectNode();
        if (isDryRun(request)) {
            result.put("UpdateToken", token);
            result.set("RuleGroupResponse", ruleGroupResponseView(updated));
            return result;
        }
        String newToken = UUID.randomUUID().toString();
        updated.put("UpdateToken", newToken);
        ruleGroups.put(ruleGroupArnOf(updated), updated);
        result.put("UpdateToken", newToken);
        result.set("RuleGroupResponse", ruleGroupResponseView(updated));
        return result;
    }

    /** A rule group referenced by a firewall policy can't be deleted (InvalidOperationException). */
    public synchronized ObjectNode deleteRuleGroup(JsonNode request, String region, String accountId) {
        ObjectNode stored = requireRuleGroup(request, region, accountId);
        String arn = ruleGroupArnOf(stored);
        if (ruleGroupAssociations(arn) > 0) {
            throw new AwsException("InvalidOperationException",
                    "Unable to delete the object because it is still in use: " + arn, 400);
        }
        ObjectNode view = ruleGroupResponseView(stored);
        view.put("RuleGroupStatus", "DELETING");
        ruleGroups.delete(arn);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("RuleGroupResponse", view);
        return result;
    }

    /**
     * Only account-owned rule groups exist in the emulator, so a MANAGED scope or any
     * managed/subscription filter yields an empty page.
     */
    public ObjectNode listRuleGroups(JsonNode request) {
        String scope = textOrNull(request, "Scope");
        String type = textOrNull(request, "Type");
        if (type != null) {
            requireEnum(type, RULE_GROUP_TYPES, "Type");
        }
        boolean managedOnly = "MANAGED".equals(scope)
                || textOrNull(request, "ManagedType") != null
                || textOrNull(request, "SubscriptionStatus") != null;
        List<ObjectNode> items = managedOnly ? List.of() : ruleGroups.scan(key -> true).stream()
                .map(stored -> (ObjectNode) stored.path("RuleGroupResponse"))
                .filter(response -> type == null || type.equals(response.path("Type").asText()))
                .sorted(Comparator.comparing(response -> response.path("RuleGroupName").asText()))
                .map(response -> objectMapper.createObjectNode()
                        .put("Name", response.path("RuleGroupName").asText())
                        .put("Arn", response.path("RuleGroupArn").asText()))
                .toList();
        return paginate(items, request, "RuleGroups");
    }

    /**
     * Summaries are derived from the stored Suricata rules (RulesString or 5-tuple
     * StatefulRules) using the options selected in SummaryConfiguration.RuleOptions.
     */
    public ObjectNode describeRuleGroupSummary(JsonNode request, String region, String accountId) {
        ObjectNode stored = requireRuleGroup(request, region, accountId);
        ObjectNode response = (ObjectNode) stored.get("RuleGroupResponse");
        if ("STATELESS".equals(response.path("Type").asText())) {
            throw new AwsException("InvalidRequestException",
                    "Rule group summaries are only available for stateful rule groups.", 400);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("RuleGroupName", response.path("RuleGroupName").asText());
        copyIfPresent(response, result, "Description");
        JsonNode ruleOptions = response.path("SummaryConfiguration").path("RuleOptions");
        if (ruleOptions.isArray() && !ruleOptions.isEmpty()) {
            Set<String> selected = new HashSet<>();
            ruleOptions.forEach(option -> selected.add(option.asText()));
            ArrayNode summaries = result.putObject("Summary").putArray("RuleSummaries");
            for (Map<String, String> options : statefulRuleOptions(stored.path("RuleGroup"))) {
                ObjectNode summary = summaries.addObject();
                putSelected(summary, selected, "SID", "SID", options.get("sid"));
                putSelected(summary, selected, "MSG", "Msg", options.get("msg"));
                putSelected(summary, selected, "METADATA", "Metadata", options.get("metadata"));
            }
        }
        return result;
    }

    public ObjectNode describeRuleGroupMetadata(JsonNode request, String region, String accountId) {
        ObjectNode stored = requireRuleGroup(request, region, accountId);
        ObjectNode response = (ObjectNode) stored.get("RuleGroupResponse");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("RuleGroupArn", response.path("RuleGroupArn").asText());
        result.put("RuleGroupName", response.path("RuleGroupName").asText());
        copyIfPresent(response, result, "Description");
        copyIfPresent(response, result, "Type");
        copyIfPresent(response, result, "Capacity");
        JsonNode statefulRuleOptions = stored.path("RuleGroup").path("StatefulRuleOptions");
        if (statefulRuleOptions.isObject()) {
            result.set("StatefulRuleOptions", statefulRuleOptions.deepCopy());
        }
        copyIfPresent(response, result, "LastModifiedTime");
        return result;
    }

    // ------------------------------------------------------------ firewall policies

    public synchronized ObjectNode createFirewallPolicy(JsonNode request, String region, String accountId) {
        requireObject(request);
        String name = requiredText(request, "FirewallPolicyName");
        ObjectNode definition = firewallPolicyDefinition(request);
        ArrayNode tags = validatedTags(request.get("Tags"), false);
        String arn = arn(region, accountId, "firewall-policy", name);
        if (firewallPolicies.get(arn).isPresent()) {
            throw new AwsException("InvalidRequestException", "FirewallPolicy already exists: " + name, 400);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallPolicyName", name);
        response.put("FirewallPolicyArn", arn);
        response.put("FirewallPolicyId", UUID.randomUUID().toString());
        copyIfPresent(request, response, "Description");
        response.put("FirewallPolicyStatus", "ACTIVE");
        response.set("Tags", tags);
        response.set("EncryptionConfiguration", encryptionConfiguration(request, null));
        response.put("LastModifiedTime", nowEpochSeconds());

        ObjectNode stored = objectMapper.createObjectNode();
        stored.set("FirewallPolicy", definition);
        stored.set("FirewallPolicyResponse", response);
        String token = UUID.randomUUID().toString();
        stored.put("UpdateToken", token);
        if (!isDryRun(request)) {
            firewallPolicies.put(arn, stored);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("UpdateToken", token);
        result.set("FirewallPolicyResponse", firewallPolicyResponseView(stored));
        return result;
    }

    public synchronized ObjectNode describeFirewallPolicy(JsonNode request, String region, String accountId) {
        ObjectNode stored = requireFirewallPolicy(request, region, accountId);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("UpdateToken", ensureStoredToken(firewallPolicies, stored, firewallPolicyArnOf(stored)));
        result.set("FirewallPolicyResponse", firewallPolicyResponseView(stored));
        if (stored.path("FirewallPolicy").isObject()) {
            result.set("FirewallPolicy", stored.get("FirewallPolicy").deepCopy());
        }
        return result;
    }

    /** In-place update guarded by UpdateToken; ARN, id and tags are retained. */
    public synchronized ObjectNode updateFirewallPolicy(JsonNode request, String region, String accountId) {
        requireObject(request);
        String token = requiredText(request, "UpdateToken");
        ObjectNode stored = requireFirewallPolicy(request, region, accountId);
        requireMatchingToken(stored, token);
        ObjectNode definition = firewallPolicyDefinition(request);

        ObjectNode updated = stored.deepCopy();
        ObjectNode response = (ObjectNode) updated.get("FirewallPolicyResponse");
        updated.set("FirewallPolicy", definition);
        replaceOrRemove(request, response, "Description");
        if (request.has("EncryptionConfiguration")) {
            response.set("EncryptionConfiguration",
                    encryptionConfiguration(request, response.get("EncryptionConfiguration")));
        }
        response.put("LastModifiedTime", nowEpochSeconds());
        ObjectNode result = objectMapper.createObjectNode();
        if (isDryRun(request)) {
            result.put("UpdateToken", token);
            result.set("FirewallPolicyResponse", firewallPolicyResponseView(updated));
            return result;
        }
        String newToken = UUID.randomUUID().toString();
        updated.put("UpdateToken", newToken);
        firewallPolicies.put(firewallPolicyArnOf(updated), updated);
        result.put("UpdateToken", newToken);
        result.set("FirewallPolicyResponse", firewallPolicyResponseView(updated));
        return result;
    }

    /** A policy still associated with a firewall can't be deleted (InvalidOperationException). */
    public synchronized ObjectNode deleteFirewallPolicy(JsonNode request, String region, String accountId) {
        ObjectNode stored = requireFirewallPolicy(request, region, accountId);
        String arn = firewallPolicyArnOf(stored);
        if (firewallPolicyAssociations(arn) > 0) {
            throw new AwsException("InvalidOperationException",
                    "Unable to delete the object because it is still in use: " + arn, 400);
        }
        ObjectNode view = firewallPolicyResponseView(stored);
        view.put("FirewallPolicyStatus", "DELETING");
        firewallPolicies.delete(arn);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("FirewallPolicyResponse", view);
        return result;
    }

    public ObjectNode listFirewallPolicies(JsonNode request) {
        List<ObjectNode> items = firewallPolicies.scan(key -> true).stream()
                .map(stored -> (ObjectNode) stored.path("FirewallPolicyResponse"))
                .sorted(Comparator.comparing(response -> response.path("FirewallPolicyName").asText()))
                .map(response -> objectMapper.createObjectNode()
                        .put("Name", response.path("FirewallPolicyName").asText())
                        .put("Arn", response.path("FirewallPolicyArn").asText()))
                .toList();
        return paginate(items, request, "FirewallPolicies");
    }

    // ----------------------------------------------------------------------- tags

    private static final int MAX_TAGS = 200;

    public synchronized ObjectNode tagResource(JsonNode request) {
        requireObject(request);
        String arn = requiredText(request, "ResourceArn");
        ArrayNode requested = validatedTags(request.get("Tags"), true);
        TagTarget target = requireTagTarget(arn);
        ArrayNode tags = target.tags();
        for (JsonNode tag : requested) {
            String key = tag.path("Key").asText();
            removeTag(tags, key);
            tags.add(tag.deepCopy());
        }
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("InvalidRequestException",
                    "A resource can have at most " + MAX_TAGS + " tags.", 400);
        }
        target.holder().set("Tags", tags);
        target.store().put(arn, target.stored());
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request) {
        requireObject(request);
        String arn = requiredText(request, "ResourceArn");
        ArrayNode keys = requiredArray(request, "TagKeys");
        TagTarget target = requireTagTarget(arn);
        ArrayNode tags = target.tags();
        for (JsonNode key : keys) {
            removeTag(tags, key.asText());
        }
        target.holder().set("Tags", tags);
        target.store().put(arn, target.stored());
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTagsForResource(JsonNode request) {
        requireObject(request);
        String arn = requiredText(request, "ResourceArn");
        TagTarget target = requireTagTarget(arn);
        List<ObjectNode> tags = new ArrayList<>();
        target.tags().forEach(tag -> tags.add((ObjectNode) tag));
        return paginate(tags, request, "Tags");
    }

    public ObjectNode createFirewall(JsonNode request, String region, String accountId) {
        String name = requiredText(request, "FirewallName");
        String firewallArn = arn(region, accountId, "firewall", name);
        ensureUnique(firewalls, firewallArn, name, "Firewall");

        ObjectNode firewall = copyObject(request);
        firewall.remove("UpdateToken");
        firewall.put("FirewallArn", firewallArn);
        firewall.put("FirewallId", deterministicHex(firewallArn, 32));
        firewall.put("FirewallName", name);
        firewall.put("FirewallPolicyChangeProtection", request.path("FirewallPolicyChangeProtection").asBoolean(true));
        firewall.put("SubnetChangeProtection", request.path("SubnetChangeProtection").asBoolean(true));
        firewall.put("DeleteProtection", request.path("DeleteProtection").asBoolean(true));
        firewall.put("AvailabilityZoneChangeProtection",
                request.path("AvailabilityZoneChangeProtection").asBoolean(false));
        rotateToken(firewall);
        firewalls.put(firewallArn, firewall);
        return firewallResponse(firewall, region);
    }

    public synchronized ObjectNode describeFirewall(String firewallArn, String firewallName, String region,
                                                    String accountId) {
        requireIdentifier(firewallArn, firewallName);
        ObjectNode firewall = find(firewalls, firewallArn, firewallName, "FirewallArn", "FirewallName");
        if (firewall == null) {
            throw notFound("Firewall", firewallArn == null ? firewallName : firewallArn);
        }
        ObjectNode response = firewallResponse(firewall, region);
        response.put("UpdateToken", ensureToken(firewall));
        return response;
    }

    /**
     * Each UpdateFirewall* operation models exactly one mutable field (botocore
     * 2020-11-12). Anything else in the raw request, including another operation's
     * field, or unmodeled members like SubnetMappings, must not be persisted, matching
     * AWS ignoring unmodeled request members and scoping each op to its own field.
     */
    private static final Map<String, String> UPDATE_ACTION_FIELDS = Map.of(
            "UpdateFirewallDescription", "Description",
            "UpdateFirewallDeleteProtection", "DeleteProtection",
            "UpdateSubnetChangeProtection", "SubnetChangeProtection",
            "UpdateFirewallPolicyChangeProtection", "FirewallPolicyChangeProtection",
            "UpdateAvailabilityZoneChangeProtection", "AvailabilityZoneChangeProtection",
            "UpdateFirewallAnalysisSettings", "EnabledAnalysisTypes");

    /**
     * Botocore documents omission as removal for UpdateFirewallDescription alone:
     * "If you omit this setting, Network Firewall removes the description for the
     * firewall." EnabledAnalysisTypes carries no equivalent statement, so an omitted
     * value there is preserved rather than inferring behaviour the model never states.
     */
    private static final Set<String> CLEARED_WHEN_OMITTED = Set.of("UpdateFirewallDescription");

    /**
     * Synchronized so that validating the caller's token against the firewall's current
     * one and rotating to a new token happen as a single atomic step. Without a common
     * lock, two overlapping calls can each read and match the same current token before
     * either commits its rotation, letting both changes apply when the second one should
     * have been rejected as stale. See {@link #associateSubnets} for the sibling case
     * this reuses the same lock for.
     */
    public synchronized ObjectNode updateFirewall(String action, JsonNode request, String region, String accountId) {
        String arn = textOrNull(request, "FirewallArn");
        String name = textOrNull(request, "FirewallName");
        ObjectNode existing = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        requireCurrentToken(existing, request);
        String field = UPDATE_ACTION_FIELDS.get(action);
        JsonNode value = request.get(field);
        if (value != null) {
            existing.set(field, value.deepCopy());
        } else if (CLEARED_WHEN_OMITTED.contains(action)) {
            existing.remove(field);
        }
        String newToken = rotateToken(existing);
        firewalls.put(existing.path("FirewallArn").asText(), existing);

        // Each UpdateFirewall* response is a flat {FirewallArn, FirewallName,
        // <field>, UpdateToken} shape (botocore 2020-11-12) -- distinct from
        // CreateFirewall/DescribeFirewall's nested {Firewall, FirewallStatus}
        // envelope that firewallResponse() builds.
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", existing.path("FirewallArn").asText());
        response.put("FirewallName", existing.path("FirewallName").asText());
        // Description and EnabledAnalysisTypes are optional on their own update ops
        // (botocore 2020-11-12 marks no required members), so a firewall created
        // without one has nothing to echo -- omit the member rather than serialising
        // the MissingNode that path() returns as an explicit null.
        JsonNode current = existing.get(field);
        if (current != null) {
            response.set(field, current.deepCopy());
        }
        response.put("UpdateToken", newToken);
        return response;
    }

    public ObjectNode deleteFirewall(String arn, String name, String region) {
        ObjectNode existing = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        String firewallArn = existing.path("FirewallArn").asText();
        if (existing.path("DeleteProtection").asBoolean(false)) {
            throw new AwsException("InvalidOperationException",
                    "Firewall has delete protection enabled: " + firewallArn, 400);
        }
        ObjectNode response = firewallResponse(existing, region);
        firewalls.delete(firewallArn);
        loggingConfigurations.delete(firewallArn);
        flowOperations.scan(key -> true).stream()
                .filter(operation -> firewallArn.equals(operation.path("FirewallArn").asText()))
                .forEach(operation -> flowOperations.delete(operation.path("FlowOperationId").asText()));
        analysisReports.scan(key -> true).stream()
                .filter(report -> firewallArn.equals(report.path("FirewallArn").asText()))
                .forEach(report -> analysisReports.delete(report.path("AnalysisReportId").asText()));
        return response;
    }

    /**
     * The four association operations are synchronized because {@link #mappingsOf}
     * hands out a detached copy and {@link #storeAndRespond} replaces the whole
     * field with it: the read-modify-write is only atomic under a common lock, and
     * without one the later of two overlapping calls silently discards the earlier
     * caller's mapping.
     */
    public synchronized ObjectNode associateSubnets(JsonNode request) {
        ObjectNode firewall = firewallForChange(request, "SubnetChangeProtection", "subnet");
        ArrayNode requestedMappings = requiredArray(request, "SubnetMappings");
        ArrayNode mappings = mappingsOf(firewall, "SubnetMappings");
        for (JsonNode requested : requestedMappings) {
            addMapping(mappings, "SubnetId", requiredText(requested, "SubnetId"), requested);
        }
        return storeAndRespond(firewall, "SubnetMappings", mappings);
    }

    /** @see #associateSubnets for why this is synchronized. */
    public synchronized ObjectNode disassociateSubnets(JsonNode request) {
        ObjectNode firewall = firewallForChange(request, "SubnetChangeProtection", "subnet");
        ArrayNode requestedIds = requiredArray(request, "SubnetIds");
        ArrayNode mappings = mappingsOf(firewall, "SubnetMappings");
        for (JsonNode subnetId : requestedIds) {
            removeMapping(mappings, "SubnetId", subnetId.asText());
        }
        return storeAndRespond(firewall, "SubnetMappings", mappings);
    }

    /** @see #associateSubnets for why this is synchronized. */
    public synchronized ObjectNode associateAvailabilityZones(JsonNode request) {
        ObjectNode firewall =
                firewallForChange(request, "AvailabilityZoneChangeProtection", "Availability Zone");
        ArrayNode requestedMappings = requiredArray(request, "AvailabilityZoneMappings");
        ArrayNode mappings = mappingsOf(firewall, "AvailabilityZoneMappings");
        for (JsonNode requested : requestedMappings) {
            addMapping(mappings, "AvailabilityZone", requiredText(requested, "AvailabilityZone"), requested);
        }
        return storeAndRespond(firewall, "AvailabilityZoneMappings", mappings);
    }

    /** @see #associateSubnets for why this is synchronized. */
    public synchronized ObjectNode disassociateAvailabilityZones(JsonNode request) {
        ObjectNode firewall =
                firewallForChange(request, "AvailabilityZoneChangeProtection", "Availability Zone");
        ArrayNode requestedMappings = requiredArray(request, "AvailabilityZoneMappings");
        ArrayNode mappings = mappingsOf(firewall, "AvailabilityZoneMappings");
        for (JsonNode requested : requestedMappings) {
            removeMapping(mappings, "AvailabilityZone", requiredText(requested, "AvailabilityZone"));
        }
        return storeAndRespond(firewall, "AvailabilityZoneMappings", mappings);
    }

    private ObjectNode firewallForChange(JsonNode request, String protectionField, String protectedResource) {
        ObjectNode firewall = require(firewalls, textOrNull(request, "FirewallArn"),
                textOrNull(request, "FirewallName"), "Firewall", "FirewallArn", "FirewallName");
        requireCurrentToken(firewall, request);
        if (firewall.path(protectionField).asBoolean(false)) {
            throw new AwsException("InvalidOperationException",
                    "Firewall has " + protectedResource + " change protection enabled: "
                            + firewall.path("FirewallArn").asText(), 400);
        }
        return firewall;
    }

    /**
     * botocore 2020-11-12 marks UpdateToken optional on every UpdateFirewall, Associate and
     * Disassociate request: omitting it makes an unconditional change, while supplying it
     * asks Network Firewall to check it against the firewall's current token and fail with
     * InvalidTokenException on a mismatch. See the UpdateFirewallDescription documentation
     * for this exact contract.
     */
    private void requireCurrentToken(ObjectNode firewall, JsonNode request) {
        String providedToken = textOrNull(request, "UpdateToken");
        if (providedToken == null) {
            return;
        }
        String currentToken = firewall.path("UpdateToken").asText(null);
        if (!providedToken.equals(currentToken)) {
            throw new AwsException("InvalidTokenException",
                    "The token you provided is stale or isn't valid for the operation.", 400);
        }
    }

    /**
     * Generates a fresh UpdateToken and stores it directly on the firewall so the next
     * mutating call can be checked against it. The token is never a member of the modeled
     * Firewall shape, so {@link #firewallResponse} strips it before nesting that object
     * under a response's {@code Firewall} field.
     */
    private String rotateToken(ObjectNode firewall) {
        String token = UUID.randomUUID().toString();
        firewall.put("UpdateToken", token);
        return token;
    }

    /**
     * A firewall persisted before UpdateToken support was added has no such field, so
     * {@code UpdateToken}'s default read is {@code null} here, matching the default
     * {@link #requireCurrentToken} compares against. Backfilling a real token on first
     * describe, rather than exposing an empty string, keeps the describe-then-submit
     * flow working for those firewalls the same way it does for ones created after
     * this change. Like every other {@link #rotateToken} caller, the backfilled token
     * is written through {@code firewalls.put} so a persistent or hybrid storage
     * backend records it; otherwise the token handed to the caller could be lost on
     * restart and a later conditional call carrying it would be rejected as stale.
     */
    private String ensureToken(ObjectNode firewall) {
        String token = firewall.path("UpdateToken").asText(null);
        if (token == null || token.isEmpty()) {
            token = rotateToken(firewall);
            firewalls.put(firewall.path("FirewallArn").asText(), firewall);
        }
        return token;
    }

    /**
     * Returns a DETACHED copy of the firewall's mapping array so per-element
     * validation can reject mid-loop without mutating stored state; the copy is
     * attached and persisted only by {@link #storeAndRespond}.
     */
    private ArrayNode mappingsOf(ObjectNode firewall, String field) {
        JsonNode existing = firewall.path(field);
        return existing.isArray() ? ((ArrayNode) existing).deepCopy() : objectMapper.createArrayNode();
    }

    private void addMapping(ArrayNode mappings, String memberField, String member, JsonNode mapping) {
        for (JsonNode existing : mappings) {
            if (member.equals(existing.path(memberField).asText(null))) {
                return;
            }
        }
        mappings.add(mapping.deepCopy());
    }

    private void removeMapping(ArrayNode mappings, String memberField, String member) {
        for (int index = mappings.size() - 1; index >= 0; index--) {
            if (member.equals(mappings.get(index).path(memberField).asText(null))) {
                mappings.remove(index);
            }
        }
    }

    private ObjectNode storeAndRespond(ObjectNode firewall, String field, ArrayNode mappings) {
        String firewallArn = firewall.path("FirewallArn").asText();
        firewall.set(field, mappings);
        String newToken = rotateToken(firewall);
        firewalls.put(firewallArn, firewall);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewallArn);
        response.put("FirewallName", firewall.path("FirewallName").asText());
        response.set(field, mappings.deepCopy());
        response.put("UpdateToken", newToken);
        return response;
    }

    public ObjectNode listFirewalls(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode result = response.putArray("Firewalls");
        List<String> vpcIds = request != null && request.path("VpcIds").isArray()
                ? java.util.stream.StreamSupport.stream(request.path("VpcIds").spliterator(), false)
                        .map(JsonNode::asText).toList()
                : List.of();
        firewalls.scan(key -> true).stream()
                .filter(firewall -> vpcIds.isEmpty() || vpcIds.contains(firewall.path("VpcId").asText()))
                .sorted(Comparator.comparing(firewall -> firewall.path("FirewallName").asText()))
                .forEach(firewall -> result.add(objectMapper.createObjectNode()
                        .put("FirewallArn", firewall.path("FirewallArn").asText())
                        .put("FirewallName", firewall.path("FirewallName").asText())));
        return response;
    }

    public ObjectNode putLoggingConfiguration(JsonNode request) {
        String arn = resolveFirewallArn(request);
        ObjectNode existing = require(firewalls, arn, textOrNull(request, "FirewallName"),
                "Firewall", "FirewallArn", "FirewallName");
        validateLoggingConfiguration(request.path("LoggingConfiguration"));
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("FirewallArn", existing.path("FirewallArn").asText());
        stored.put("FirewallName", existing.path("FirewallName").asText());
        stored.set("LoggingConfiguration", request.path("LoggingConfiguration").deepCopy());
        if (request.has("EnableMonitoringDashboard")) {
            stored.set("EnableMonitoringDashboard", request.get("EnableMonitoringDashboard").deepCopy());
        }
        loggingConfigurations.put(existing.path("FirewallArn").asText(), stored);
        return stored.deepCopy();
    }

    /**
     * The whole LoggingConfiguration is stored verbatim, so the two enums inside each
     * LogDestinationConfig have to be checked here: LogType (ALERT/FLOW/TLS) and LogDestinationType
     * (S3/CloudWatchLogs/KinesisDataFirehose, case-sensitive as the model spells them).
     */
    private void validateLoggingConfiguration(JsonNode loggingConfiguration) {
        if (loggingConfiguration == null || !loggingConfiguration.isObject()) {
            return;
        }
        for (JsonNode config : loggingConfiguration.path("LogDestinationConfigs")) {
            requireEnum(textOrNull(config, "LogType"), LOG_TYPES, "LogType");
            requireEnum(textOrNull(config, "LogDestinationType"), LOG_DESTINATION_TYPES,
                    "LogDestinationType");
        }
    }

    public ObjectNode describeLoggingConfiguration(String arn, String name) {
        ObjectNode firewall = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        ObjectNode logging = loggingConfigurations.get(firewall.path("FirewallArn").asText()).orElse(null);
        if (logging == null) {
            ObjectNode empty = objectMapper.createObjectNode();
            empty.put("FirewallArn", firewall.path("FirewallArn").asText());
            empty.put("FirewallName", firewall.path("FirewallName").asText());
            empty.set("LoggingConfiguration", objectMapper.createObjectNode()
                    .set("LogDestinationConfigs", objectMapper.createArrayNode()));
            return empty;
        }
        return logging.deepCopy();
    }

    public ObjectNode deleteLoggingConfiguration(String arn, String name) {
        ObjectNode firewall = require(firewalls, arn, name, "Firewall", "FirewallArn", "FirewallName");
        loggingConfigurations.delete(firewall.path("FirewallArn").asText());
        return objectMapper.createObjectNode();
    }

    public ObjectNode findFirewall(String arn) {
        return firewalls.get(arn).map(ObjectNode::deepCopy).orElse(null);
    }

    // ---------------------------------------------------------------- flow operations

    private static final int MAX_FLOW_FILTERS = 20;
    private static final Set<String> FLOW_OPERATION_TYPES = Set.of("FLOW_CAPTURE", "FLOW_FLUSH");
    private static final Set<String> ANALYSIS_TYPES = Set.of("TLS_SNI", "HTTP_HOST");

    public ObjectNode startFlowCapture(JsonNode request, String region) {
        return startFlowOperation(request, region, "FLOW_CAPTURE");
    }

    public ObjectNode startFlowFlush(JsonNode request, String region) {
        return startFlowOperation(request, region, "FLOW_FLUSH");
    }

    /**
     * Floci has no data plane behind a firewall endpoint, so its flow table is always empty: a
     * capture or flush runs against the real firewall record, finds no tracked flows, and
     * completes with an empty result set. The start response reports IN_PROGRESS as AWS does;
     * the stored operation has already settled to COMPLETED.
     */
    private synchronized ObjectNode startFlowOperation(JsonNode request, String region, String type) {
        requireObject(request);
        ObjectNode firewall = requireFirewallByArn(request);
        ObjectNode scope = flowOperationScope(firewall, request, region);
        ArrayNode filters = validatedFlowFilters(request.get("FlowFilters"));
        JsonNode minimumAge = request.get("MinimumFlowAgeInSeconds");
        if (minimumAge != null && !minimumAge.isNull() && (!minimumAge.canConvertToInt() || minimumAge.asInt() < 0)) {
            throw new AwsException("InvalidRequestException",
                    "MinimumFlowAgeInSeconds must be a non-negative integer.", 400);
        }

        String firewallArn = firewall.path("FirewallArn").asText();
        String flowOperationId = UUID.randomUUID().toString();
        ObjectNode operation = objectMapper.createObjectNode();
        operation.put("FirewallArn", firewallArn);
        operation.setAll(scope);
        operation.put("FlowOperationId", flowOperationId);
        operation.put("FlowOperationType", type);
        operation.put("FlowOperationStatus", "COMPLETED");
        operation.put("FlowRequestTimestamp", nowEpochSeconds());
        ObjectNode definition = operation.putObject("FlowOperation");
        if (minimumAge != null && !minimumAge.isNull()) {
            definition.put("MinimumFlowAgeInSeconds", minimumAge.asInt());
        }
        definition.set("FlowFilters", filters);
        operation.putArray("Flows");
        flowOperations.put(flowOperationId, operation);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewallArn);
        response.put("FlowOperationId", flowOperationId);
        response.put("FlowOperationStatus", "IN_PROGRESS");
        return response;
    }

    public ObjectNode describeFlowOperation(JsonNode request) {
        requireObject(request);
        ObjectNode operation = requireFlowOperation(request);
        ObjectNode response = objectMapper.createObjectNode();
        for (String field : List.of("FirewallArn", "AvailabilityZone", "VpcEndpointAssociationArn",
                "VpcEndpointId", "FlowOperationId", "FlowOperationType", "FlowOperationStatus",
                "StatusMessage", "FlowRequestTimestamp", "FlowOperation")) {
            copyIfPresent(operation, response, field);
        }
        return response;
    }

    public ObjectNode listFlowOperations(JsonNode request) {
        requireObject(request);
        ObjectNode firewall = requireFirewallByArn(request);
        String firewallArn = firewall.path("FirewallArn").asText();
        String type = textOrNull(request, "FlowOperationType");
        if (type != null) {
            requireEnum(type, FLOW_OPERATION_TYPES, "FlowOperationType");
        }
        String availabilityZone = textOrNull(request, "AvailabilityZone");
        String vpcEndpointId = textOrNull(request, "VpcEndpointId");
        String vpcEndpointAssociationArn = textOrNull(request, "VpcEndpointAssociationArn");
        List<ObjectNode> items = new ArrayList<>();
        flowOperations.scan(key -> true).stream()
                .filter(operation -> firewallArn.equals(operation.path("FirewallArn").asText()))
                .filter(operation -> type == null || type.equals(operation.path("FlowOperationType").asText()))
                .filter(operation -> availabilityZone == null
                        || availabilityZone.equals(operation.path("AvailabilityZone").asText(null)))
                .filter(operation -> vpcEndpointId == null
                        || vpcEndpointId.equals(operation.path("VpcEndpointId").asText(null)))
                .filter(operation -> vpcEndpointAssociationArn == null
                        || vpcEndpointAssociationArn.equals(operation.path("VpcEndpointAssociationArn").asText(null)))
                .sorted(Comparator.comparingLong((ObjectNode operation) ->
                                operation.path("FlowRequestTimestamp").asLong()).reversed()
                        .thenComparing(operation -> operation.path("FlowOperationId").asText()))
                .forEach(operation -> {
                    ObjectNode metadata = objectMapper.createObjectNode();
                    metadata.put("FlowOperationId", operation.path("FlowOperationId").asText());
                    metadata.put("FlowOperationType", operation.path("FlowOperationType").asText());
                    metadata.set("FlowRequestTimestamp", operation.path("FlowRequestTimestamp").deepCopy());
                    metadata.put("FlowOperationStatus", operation.path("FlowOperationStatus").asText());
                    items.add(metadata);
                });
        return paginate(items, request, "FlowOperations");
    }

    public ObjectNode listFlowOperationResults(JsonNode request) {
        requireObject(request);
        ObjectNode operation = requireFlowOperation(request);
        List<ObjectNode> flows = new ArrayList<>();
        operation.path("Flows").forEach(flow -> flows.add((ObjectNode) flow));
        ObjectNode response = paginate(flows, request, "Flows");
        for (String field : List.of("FirewallArn", "AvailabilityZone", "VpcEndpointAssociationArn",
                "VpcEndpointId", "FlowOperationId", "FlowOperationStatus", "StatusMessage",
                "FlowRequestTimestamp")) {
            copyIfPresent(operation, response, field);
        }
        return response;
    }

    private ObjectNode requireFirewallByArn(JsonNode request) {
        String firewallArn = requiredText(request, "FirewallArn");
        ObjectNode firewall = firewalls.get(firewallArn).orElse(null);
        if (firewall == null) {
            throw notFound("Firewall", firewallArn);
        }
        return firewall;
    }

    private ObjectNode requireFlowOperation(JsonNode request) {
        ObjectNode firewall = requireFirewallByArn(request);
        String flowOperationId = requiredText(request, "FlowOperationId");
        ObjectNode operation = flowOperations.get(flowOperationId).orElse(null);
        if (operation == null
                || !firewall.path("FirewallArn").asText().equals(operation.path("FirewallArn").asText())) {
            throw notFound("Flow operation", flowOperationId);
        }
        return operation;
    }

    /**
     * A flow operation is scoped to one firewall endpoint, named by Availability Zone or by VPC
     * endpoint. Either must be one the firewall actually has. Floci models no VPC endpoint
     * associations, so an association ARN can never resolve.
     */
    private ObjectNode flowOperationScope(ObjectNode firewall, JsonNode request, String region) {
        String availabilityZone = textOrNull(request, "AvailabilityZone");
        String vpcEndpointId = textOrNull(request, "VpcEndpointId");
        String vpcEndpointAssociationArn = textOrNull(request, "VpcEndpointAssociationArn");
        if (vpcEndpointAssociationArn != null) {
            throw notFound("VpcEndpointAssociation", vpcEndpointAssociationArn);
        }
        JsonNode syncStates = firewallResponse(firewall, region).path("FirewallStatus").path("SyncStates");
        ObjectNode scope = objectMapper.createObjectNode();
        if (availabilityZone != null) {
            if (!syncStates.has(availabilityZone)) {
                throw new AwsException("InvalidRequestException",
                        "The firewall has no endpoint in Availability Zone " + availabilityZone + ".", 400);
            }
            scope.put("AvailabilityZone", availabilityZone);
        }
        if (vpcEndpointId != null) {
            boolean known = false;
            for (JsonNode state : syncStates) {
                if (vpcEndpointId.equals(state.path("Attachment").path("EndpointId").asText(null))) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new AwsException("InvalidRequestException",
                        "The firewall has no VPC endpoint " + vpcEndpointId + ".", 400);
            }
            scope.put("VpcEndpointId", vpcEndpointId);
        }
        return scope;
    }

    private ArrayNode validatedFlowFilters(JsonNode filters) {
        if (filters == null || !filters.isArray() || filters.isEmpty()) {
            throw new AwsException("InvalidRequestException", "FlowFilters is required.", 400);
        }
        if (filters.size() > MAX_FLOW_FILTERS) {
            throw new AwsException("InvalidRequestException",
                    "FlowFilters can contain at most " + MAX_FLOW_FILTERS + " filters.", 400);
        }
        for (JsonNode filter : filters) {
            if (!filter.isObject()) {
                throw new AwsException("InvalidRequestException", "Each FlowFilter must be an object.", 400);
            }
            for (String addressField : List.of("SourceAddress", "DestinationAddress")) {
                JsonNode address = filter.get(addressField);
                if (address != null && !address.isNull()) {
                    String definition = textOrNull(address, "AddressDefinition");
                    if (definition == null || !isCidr(definition)) {
                        throw new AwsException("InvalidRequestException",
                                addressField + ".AddressDefinition must be a CIDR block.", 400);
                    }
                }
            }
        }
        return ((ArrayNode) filters).deepCopy();
    }

    static boolean isCidr(String value) {
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) {
            return false;
        }
        String address = value.substring(0, slash);
        int prefix;
        try {
            prefix = Integer.parseInt(value.substring(slash + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        if (address.contains(":")) {
            return prefix >= 0 && prefix <= 128 && address.matches("[0-9A-Fa-f:.]+");
        }
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4 || prefix < 0 || prefix > 32) {
            return false;
        }
        for (String octet : octets) {
            if (!octet.matches("\\d{1,3}") || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- analysis reports

    /**
     * A report can only be generated for an analysis type enabled on the firewall through
     * UpdateFirewallAnalysisSettings. Floci carries no traffic through a firewall, so a report
     * completes immediately with no findings.
     */
    public synchronized ObjectNode startAnalysisReport(JsonNode request) {
        requireObject(request);
        ObjectNode firewall = require(firewalls, textOrNull(request, "FirewallArn"),
                textOrNull(request, "FirewallName"), "Firewall", "FirewallArn", "FirewallName");
        String analysisType = requiredText(request, "AnalysisType");
        requireEnum(analysisType, ANALYSIS_TYPES, "AnalysisType");
        if (!containsText(firewall.path("EnabledAnalysisTypes"), analysisType)) {
            throw new AwsException("InvalidRequestException",
                    "Analysis type " + analysisType + " is not enabled for firewall "
                            + firewall.path("FirewallArn").asText() + ".", 400);
        }
        long now = nowEpochSeconds();
        String reportId = UUID.randomUUID().toString();
        ObjectNode report = objectMapper.createObjectNode();
        report.put("AnalysisReportId", reportId);
        report.put("FirewallArn", firewall.path("FirewallArn").asText());
        report.put("AnalysisType", analysisType);
        report.put("Status", "COMPLETED");
        report.put("ReportTime", now);
        report.put("StartTime", now - Duration.ofDays(30).toSeconds());
        report.put("EndTime", now);
        report.putArray("AnalysisReportResults");
        analysisReports.put(reportId, report);
        return objectMapper.createObjectNode().put("AnalysisReportId", reportId);
    }

    public ObjectNode listAnalysisReports(JsonNode request) {
        requireObject(request);
        ObjectNode firewall = require(firewalls, textOrNull(request, "FirewallArn"),
                textOrNull(request, "FirewallName"), "Firewall", "FirewallArn", "FirewallName");
        String firewallArn = firewall.path("FirewallArn").asText();
        List<ObjectNode> items = new ArrayList<>();
        analysisReports.scan(key -> true).stream()
                .filter(report -> firewallArn.equals(report.path("FirewallArn").asText()))
                .sorted(Comparator.comparingLong((ObjectNode report) -> report.path("ReportTime").asLong())
                        .reversed()
                        .thenComparing(report -> report.path("AnalysisReportId").asText()))
                .forEach(report -> {
                    ObjectNode summary = objectMapper.createObjectNode();
                    for (String field : List.of("AnalysisReportId", "AnalysisType", "ReportTime", "Status")) {
                        copyIfPresent(report, summary, field);
                    }
                    items.add(summary);
                });
        return paginate(items, request, "AnalysisReports");
    }

    public ObjectNode getAnalysisReportResults(JsonNode request) {
        requireObject(request);
        ObjectNode firewall = require(firewalls, textOrNull(request, "FirewallArn"),
                textOrNull(request, "FirewallName"), "Firewall", "FirewallArn", "FirewallName");
        String reportId = requiredText(request, "AnalysisReportId");
        ObjectNode report = analysisReports.get(reportId).orElse(null);
        if (report == null
                || !firewall.path("FirewallArn").asText().equals(report.path("FirewallArn").asText())) {
            throw notFound("Analysis report", reportId);
        }
        List<ObjectNode> results = new ArrayList<>();
        report.path("AnalysisReportResults").forEach(result -> results.add((ObjectNode) result));
        ObjectNode response = paginate(results, request, "AnalysisReportResults");
        for (String field : List.of("Status", "StartTime", "EndTime", "ReportTime", "AnalysisType")) {
            copyIfPresent(report, response, field);
        }
        return response;
    }

    private static boolean containsText(JsonNode array, String expected) {
        for (JsonNode value : array) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------- rule group / policy helpers

    /** Members a legacy (pre-normalisation) record may carry that aren't in the response shapes. */
    private static final List<String> NON_RESPONSE_FIELDS = List.of("ResourceArn", "ResourceName", "Rules",
            "RuleGroup", "FirewallPolicy", "DryRun", "AnalyzeRuleGroup", "UpdateToken");
    private static final Set<String> ENCRYPTION_TYPES = Set.of("CUSTOMER_KMS", "AWS_OWNED_KMS_KEY");
    private static final int MAX_CAPACITY = 30000;
    private static final int MAX_PAGE_SIZE = 100;

    private ObjectNode requireRuleGroup(JsonNode request, String region, String accountId) {
        String arn = textOrNull(request, "RuleGroupArn");
        String name = textOrNull(request, "RuleGroupName");
        String type = textOrNull(request, "Type");
        requireIdentifier(arn, name);
        if (type != null) {
            requireEnum(type, RULE_GROUP_TYPES, "Type");
        }
        ObjectNode found;
        if (arn != null && !arn.isBlank()) {
            found = ruleGroups.get(arn).orElse(null);
        } else if (type != null) {
            found = ruleGroups.get(ruleGroupArn(region, accountId, type, name)).orElse(null);
        } else {
            found = ruleGroups.scan(key -> true).stream()
                    .filter(stored -> name.equals(stored.path("RuleGroupResponse").path("RuleGroupName").asText(null)))
                    .findFirst().orElse(null);
        }
        if (found == null) {
            throw notFound("RuleGroup", arn != null && !arn.isBlank() ? arn : name);
        }
        return found;
    }

    private ObjectNode requireFirewallPolicy(JsonNode request, String region, String accountId) {
        String arn = textOrNull(request, "FirewallPolicyArn");
        String name = textOrNull(request, "FirewallPolicyName");
        requireIdentifier(arn, name);
        String key = arn != null && !arn.isBlank() ? arn : arn(region, accountId, "firewall-policy", name);
        ObjectNode found = firewallPolicies.get(key).orElse(null);
        if (found == null) {
            throw notFound("FirewallPolicy", arn != null && !arn.isBlank() ? arn : name);
        }
        return found;
    }

    private static String ruleGroupArnOf(JsonNode stored) {
        return stored.path("RuleGroupResponse").path("RuleGroupArn").asText();
    }

    private static String firewallPolicyArnOf(JsonNode stored) {
        return stored.path("FirewallPolicyResponse").path("FirewallPolicyArn").asText();
    }

    private ObjectNode ruleGroupResponseView(ObjectNode stored) {
        ObjectNode view = ((ObjectNode) stored.path("RuleGroupResponse")).deepCopy();
        view.remove(NON_RESPONSE_FIELDS);
        if (!view.hasNonNull("RuleGroupStatus")) {
            view.put("RuleGroupStatus", "ACTIVE");
        }
        view.put("NumberOfAssociations", ruleGroupAssociations(view.path("RuleGroupArn").asText()));
        return view;
    }

    private ObjectNode firewallPolicyResponseView(ObjectNode stored) {
        ObjectNode view = ((ObjectNode) stored.path("FirewallPolicyResponse")).deepCopy();
        view.remove(NON_RESPONSE_FIELDS);
        if (!view.hasNonNull("FirewallPolicyStatus")) {
            view.put("FirewallPolicyStatus", "ACTIVE");
        }
        view.put("NumberOfAssociations", firewallPolicyAssociations(view.path("FirewallPolicyArn").asText()));
        return view;
    }

    /** Number of firewall policies whose stateless or stateful references name this rule group. */
    private int ruleGroupAssociations(String ruleGroupArn) {
        return (int) firewallPolicies.scan(key -> true).stream()
                .filter(stored -> referencesRuleGroup(stored.path("FirewallPolicy"), ruleGroupArn))
                .count();
    }

    private static boolean referencesRuleGroup(JsonNode policy, String ruleGroupArn) {
        for (String field : List.of("StatelessRuleGroupReferences", "StatefulRuleGroupReferences")) {
            for (JsonNode reference : policy.path(field)) {
                if (ruleGroupArn.equals(reference.path("ResourceArn").asText(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int firewallPolicyAssociations(String policyArn) {
        return (int) firewalls.scan(key -> true).stream()
                .filter(firewall -> policyArn.equals(firewall.path("FirewallPolicyArn").asText(null)))
                .count();
    }

    private String ensureStoredToken(StorageBackend<String, ObjectNode> store, ObjectNode stored, String arn) {
        String token = stored.path("UpdateToken").asText(null);
        if (token == null || token.isEmpty()) {
            token = UUID.randomUUID().toString();
            stored.put("UpdateToken", token);
            store.put(arn, stored);
        }
        return token;
    }

    private static void requireMatchingToken(ObjectNode stored, String providedToken) {
        if (!providedToken.equals(stored.path("UpdateToken").asText(null))) {
            throw new AwsException("InvalidTokenException",
                    "The token you provided is stale or isn't valid for the operation.", 400);
        }
    }

    /**
     * Exactly one of RuleGroup or Rules must be supplied. Rules is a stateful Suricata string,
     * reported back by DescribeRuleGroup as RuleGroup.RulesSource.RulesString.
     */
    private ObjectNode ruleGroupDefinition(JsonNode request, String type) {
        JsonNode ruleGroup = request.get("RuleGroup");
        String rules = textOrNull(request, "Rules");
        boolean hasRuleGroup = ruleGroup != null && !ruleGroup.isNull();
        if (hasRuleGroup && rules != null) {
            throw new AwsException("InvalidRequestException",
                    "You must provide either RuleGroup or Rules, but not both.", 400);
        }
        if (!hasRuleGroup && rules == null) {
            throw new AwsException("InvalidRequestException",
                    "You must provide either RuleGroup or Rules.", 400);
        }
        if (rules != null) {
            if ("STATELESS".equals(type)) {
                throw new AwsException("InvalidRequestException",
                        "Rules can only be specified for stateful rule groups.", 400);
            }
            ObjectNode definition = objectMapper.createObjectNode();
            definition.putObject("RulesSource").put("RulesString", rules);
            return definition;
        }
        if (!ruleGroup.isObject() || !ruleGroup.path("RulesSource").isObject()) {
            throw new AwsException("InvalidRequestException", "RuleGroup.RulesSource is required.", 400);
        }
        return ruleGroup.deepCopy();
    }

    private ObjectNode firewallPolicyDefinition(JsonNode request) {
        JsonNode policy = request.get("FirewallPolicy");
        if (policy == null || !policy.isObject()) {
            throw new AwsException("InvalidRequestException", "FirewallPolicy is required.", 400);
        }
        requiredArray(policy, "StatelessDefaultActions");
        requiredArray(policy, "StatelessFragmentDefaultActions");
        return policy.deepCopy();
    }

    private static int requiredCapacity(JsonNode request) {
        JsonNode capacity = request.get("Capacity");
        if (capacity == null || !capacity.isNumber()) {
            throw new AwsException("InvalidRequestException", "Capacity is required.", 400);
        }
        int value = capacity.asInt();
        if (value < 1 || value > MAX_CAPACITY) {
            throw new AwsException("InvalidRequestException",
                    "Capacity must be between 1 and " + MAX_CAPACITY + ".", 400);
        }
        return value;
    }

    private JsonNode encryptionConfiguration(JsonNode request, JsonNode existing) {
        JsonNode requested = request.get("EncryptionConfiguration");
        if (requested == null || requested.isNull()) {
            if (existing != null && !existing.isNull()) {
                return existing.deepCopy();
            }
            return objectMapper.createObjectNode().put("Type", "AWS_OWNED_KMS_KEY");
        }
        if (!requested.isObject()) {
            throw new AwsException("InvalidRequestException", "EncryptionConfiguration must be an object.", 400);
        }
        String type = requiredText(requested, "Type");
        requireEnum(type, ENCRYPTION_TYPES, "EncryptionConfiguration.Type");
        if ("CUSTOMER_KMS".equals(type) && textOrNull(requested, "KeyId") == null) {
            throw new AwsException("InvalidRequestException",
                    "EncryptionConfiguration.KeyId is required for CUSTOMER_KMS encryption.", 400);
        }
        return requested.deepCopy();
    }

    private ArrayNode validatedTags(JsonNode tags, boolean required) {
        ArrayNode result = objectMapper.createArrayNode();
        if (tags == null || tags.isNull()) {
            if (required) {
                throw new AwsException("InvalidRequestException", "Tags is required.", 400);
            }
            return result;
        }
        if (!tags.isArray() || (required && tags.isEmpty())) {
            throw new AwsException("InvalidRequestException", "Tags must be a non-empty list.", 400);
        }
        if (tags.size() > MAX_TAGS) {
            throw new AwsException("InvalidRequestException",
                    "A resource can have at most " + MAX_TAGS + " tags.", 400);
        }
        for (JsonNode tag : tags) {
            String key = textOrNull(tag, "Key");
            String value = textOrNull(tag, "Value");
            if (key == null || key.isBlank() || value == null) {
                throw new AwsException("InvalidRequestException", "Each tag requires a Key and a Value.", 400);
            }
            removeTag(result, key);
            result.addObject().put("Key", key).put("Value", value);
        }
        return result;
    }

    private static void removeTag(ArrayNode tags, String key) {
        for (int index = tags.size() - 1; index >= 0; index--) {
            if (key.equals(tags.get(index).path("Key").asText(null))) {
                tags.remove(index);
            }
        }
    }

    /** The stored record holding a taggable resource and the object whose {@code Tags} member it owns. */
    private record TagTarget(StorageBackend<String, ObjectNode> store, ObjectNode stored, ObjectNode holder) {
        ArrayNode tags() {
            JsonNode tags = holder.get("Tags");
            return tags != null && tags.isArray()
                    ? ((ArrayNode) tags).deepCopy()
                    : JsonNodeFactory.instance.arrayNode();
        }
    }

    private TagTarget requireTagTarget(String arn) {
        ObjectNode ruleGroup = ruleGroups.get(arn).orElse(null);
        if (ruleGroup != null && ruleGroup.path("RuleGroupResponse").isObject()) {
            return new TagTarget(ruleGroups, ruleGroup, (ObjectNode) ruleGroup.get("RuleGroupResponse"));
        }
        ObjectNode policy = firewallPolicies.get(arn).orElse(null);
        if (policy != null && policy.path("FirewallPolicyResponse").isObject()) {
            return new TagTarget(firewallPolicies, policy, (ObjectNode) policy.get("FirewallPolicyResponse"));
        }
        ObjectNode firewall = firewalls.get(arn).orElse(null);
        if (firewall != null) {
            return new TagTarget(firewalls, firewall, firewall);
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 400);
    }

    /** Opaque offset-based pagination with the service's 1-100 MaxResults bound. */
    private ObjectNode paginate(List<ObjectNode> items, JsonNode request, String field) {
        int offset = 0;
        String nextToken = textOrNull(request, "NextToken");
        if (nextToken != null) {
            try {
                offset = Integer.parseInt(new String(Base64.getUrlDecoder().decode(nextToken),
                        StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                offset = -1;
            }
            if (offset < 0 || offset > items.size()) {
                throw new AwsException("InvalidRequestException", "The NextToken is not valid.", 400);
            }
        }
        int pageSize = MAX_PAGE_SIZE;
        JsonNode maxResults = request == null ? null : request.get("MaxResults");
        if (maxResults != null && !maxResults.isNull()) {
            pageSize = maxResults.asInt();
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new AwsException("InvalidRequestException",
                        "MaxResults must be between 1 and " + MAX_PAGE_SIZE + ".", 400);
            }
        }
        int end = Math.min(items.size(), offset + pageSize);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode page = response.putArray(field);
        items.subList(offset, end).forEach(item -> page.add(item.deepCopy()));
        if (end < items.size()) {
            response.put("NextToken", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(Integer.toString(end).getBytes(StandardCharsets.UTF_8)));
        }
        return response;
    }

    /**
     * Per-rule option maps (lower-cased keyword to unquoted value) for a stateful rule group:
     * one per Suricata line in RulesSource.RulesString, and one per 5-tuple StatefulRules entry.
     * Domain lists (RulesSourceList) carry no per-rule sid/msg and contribute nothing.
     */
    static List<Map<String, String>> statefulRuleOptions(JsonNode ruleGroup) {
        List<Map<String, String>> result = new ArrayList<>();
        JsonNode source = ruleGroup.path("RulesSource");
        String rulesString = textOrNull(source, "RulesString");
        if (rulesString != null) {
            for (String line : rulesString.split("\\R")) {
                String rule = line.trim();
                if (!rule.isEmpty() && !rule.startsWith("#")) {
                    result.add(suricataOptions(rule));
                }
            }
        }
        for (JsonNode rule : source.path("StatefulRules")) {
            Map<String, String> options = new LinkedHashMap<>();
            for (JsonNode option : rule.path("RuleOptions")) {
                String keyword = option.path("Keyword").asText("").trim().toLowerCase(Locale.ROOT);
                JsonNode settings = option.path("Settings");
                String value = settings.isArray() && !settings.isEmpty() ? settings.get(0).asText() : "";
                if (!keyword.isEmpty()) {
                    options.putIfAbsent(keyword, unquote(value.trim()));
                }
            }
            result.add(options);
        }
        return result;
    }

    /** Parses the {@code (key:value; ...)} option block of one Suricata rule. */
    static Map<String, String> suricataOptions(String rule) {
        Map<String, String> options = new LinkedHashMap<>();
        int open = rule.indexOf('(');
        int close = rule.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return options;
        }
        String body = rule.substring(open + 1, close);
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (char c : body.toCharArray()) {
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (c == '"') {
                current.append(c);
                quoted = !quoted;
            } else if (c == ';' && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        for (String part : parts) {
            String option = part.trim();
            if (option.isEmpty()) {
                continue;
            }
            int colon = option.indexOf(':');
            String key = (colon < 0 ? option : option.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : option.substring(colon + 1).trim();
            options.putIfAbsent(key, unquote(value));
        }
        return options;
    }

    private static String unquote(String value) {
        String inner = value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
        StringBuilder result = new StringBuilder(inner.length());
        boolean escaped = false;
        for (char c : inner.toCharArray()) {
            if (escaped) {
                result.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static void putSelected(ObjectNode summary, Set<String> selected, String option, String field,
                                    String value) {
        if (selected.contains(option) && value != null) {
            summary.put(field, value);
        }
    }

    private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) {
            to.set(field, value.deepCopy());
        }
    }

    private static void replaceOrRemove(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) {
            to.set(field, value.deepCopy());
        } else {
            to.remove(field);
        }
    }

    private static boolean isDryRun(JsonNode request) {
        return request.path("DryRun").asBoolean(false);
    }

    private static long nowEpochSeconds() {
        return Instant.now().getEpochSecond();
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new AwsException("InvalidRequestException", "A JSON request object is required.", 400);
        }
    }

    /**
     * UpdateToken is stored directly on the firewall so {@link #requireCurrentToken} can
     * read it back, but botocore's Firewall shape has no such member, so the nested
     * {@code Firewall} view built here must have it stripped.
     */
    private ObjectNode firewallResponse(ObjectNode firewall, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode firewallView = firewall.deepCopy();
        firewallView.remove("UpdateToken");
        response.set("Firewall", firewallView);
        ObjectNode status = response.putObject("FirewallStatus");
        status.put("ConfigurationSyncStateSummary", "IN_SYNC");
        status.put("Status", "READY");
        ObjectNode syncStates = status.putObject("SyncStates");
        JsonNode mappings = firewall.path("SubnetMappings");
        // Only a firewall created without any SubnetMappings gets synthetic endpoints; once the
        // field exists, disassociating every subnet must leave the sync states empty.
        if (mappings.isArray()) {
            int index = 0;
            for (JsonNode mapping : mappings) {
                addAttachment(syncStates, region + (char) ('a' + index++),
                        mapping.path("SubnetId").asText(), firewall.path("FirewallArn").asText());
            }
        } else {
            for (int index = 0; index < AVAILABILITY_ZONES_PER_REGION; index++) {
                String availabilityZone = region + (char) ('a' + index);
                String subnetId = "subnet-" + deterministicHex(
                        firewall.path("FirewallArn").asText() + "|subnet|" + availabilityZone, 17);
                addAttachment(syncStates, availabilityZone, subnetId, firewall.path("FirewallArn").asText());
            }
        }
        return response;
    }

    private void addAttachment(ObjectNode syncStates, String availabilityZone, String subnetId, String arn) {
        ObjectNode attachment = syncStates.putObject(availabilityZone).putObject("Attachment");
        attachment.put("EndpointId", "vpce-" + deterministicHex(arn + "|" + availabilityZone, 17));
        attachment.put("Status", "READY");
        attachment.put("SubnetId", subnetId);
    }

    /**
     * botocore 2020-11-12 models no {@code *AlreadyExist*} shape for CreateFirewall,
     * CreateRuleGroup or CreateFirewallPolicy: their only modeled client-fault error for
     * a name collision is InvalidRequestException, so that is what a typed SDK client can
     * actually deserialize here.
     */
    private void ensureUnique(StorageBackend<String, ObjectNode> store, String arn, String name, String kind) {
        if (store.get(arn).isPresent()) {
            throw new AwsException("InvalidRequestException", kind + " already exists: " + name, 400);
        }
    }

    private ObjectNode require(StorageBackend<String, ObjectNode> store, String arn, String name,
                               String kind, String arnField, String nameField) {
        requireIdentifier(arn, name);
        ObjectNode result = find(store, arn, name, arnField, nameField);
        if (result == null) {
            throw notFound(kind, arn == null ? name : arn);
        }
        return result;
    }

    private ObjectNode find(StorageBackend<String, ObjectNode> store, String arn, String name,
                            String arnField, String nameField) {
        if (arn != null && !arn.isBlank()) {
            ObjectNode direct = store.get(arn).orElse(null);
            if (direct != null) {
                return direct;
            }
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        return store.scan(key -> true).stream()
                .filter(node -> containsValue(node, nameField, name))
                .findFirst().orElse(null);
    }

    private boolean containsValue(JsonNode node, String field, String expected) {
        if (expected.equals(node.path(field).asText(null))) {
            return true;
        }
        for (JsonNode child : node) {
            if (child.isObject() && expected.equals(child.path(field).asText(null))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The model enumerates {@code Type} as STATELESS, STATEFUL or STATEFUL_DOMAIN, and the value is
     * both persisted and folded into the ARN. Only STATELESS gets the stateless ARN prefix; the two
     * stateful variants share {@code stateful-rulegroup}, as AWS does. Left unchecked, any unmodelled
     * value silently fell through to the stateful prefix and was stored as a rule group AWS would
     * have rejected.
     */
    private static String ruleGroupArn(String region, String accountId, String type, String name) {
        String prefix = "STATELESS".equals(type) ? "stateless-rulegroup" : "stateful-rulegroup";
        return arn(region, accountId, prefix, name);
    }

    private static void requireEnum(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw new AwsException("InvalidRequestException",
                    field + " must be one of " + allowed + ".", 400);
        }
    }

    private String resolveFirewallArn(JsonNode request) {
        String arn = textOrNull(request, "FirewallArn");
        if (arn != null) {
            return arn;
        }
        String name = textOrNull(request, "FirewallName");
        ObjectNode firewall = find(firewalls, null, name, "FirewallArn", "FirewallName");
        return firewall == null ? null : firewall.path("FirewallArn").asText();
    }

    private ObjectNode copyObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new AwsException("InvalidRequestException", "A JSON request object is required.", 400);
        }
        return ((ObjectNode) request).deepCopy();
    }

    private static String requiredText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        return value;
    }

    private static ArrayNode requiredArray(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new AwsException("InvalidRequestException", field + " is required.", 400);
        }
        return (ArrayNode) value;
    }

    private static void requireIdentifier(String arn, String name) {
        if ((arn == null || arn.isBlank()) && (name == null || name.isBlank())) {
            throw new AwsException("InvalidRequestException",
                    "Either a resource name or ARN must be specified.", 400);
        }
    }

    private static AwsException notFound(String kind, String identifier) {
        return new AwsException("ResourceNotFoundException", kind + " not found: " + identifier, 400);
    }

    private static String arn(String region, String accountId, String type, String name) {
        return "arn:aws:network-firewall:" + region + ":" + accountId + ":" + type + "/" + name;
    }

    static String textOrNull(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String deterministicHex(String value, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** @see #associateSubnets for why this is synchronized. */
    public synchronized ObjectNode associateFirewallPolicy(JsonNode request, String region, String accountId) {
        String policyArn = requiredText(request, "FirewallPolicyArn");
        ObjectNode firewall =
                firewallForChange(request, "FirewallPolicyChangeProtection", "firewall policy");
        if (firewallPolicies.get(policyArn).isEmpty()) {
            throw notFound("FirewallPolicy", policyArn);
        }
        String firewallArn = firewall.path("FirewallArn").asText();
        firewall.put("FirewallPolicyArn", policyArn);
        String newToken = rotateToken(firewall);
        firewalls.put(firewallArn, firewall);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewallArn);
        response.put("FirewallName", firewall.path("FirewallName").asText());
        response.put("FirewallPolicyArn", policyArn);
        response.put("UpdateToken", newToken);
        return response;
    }
}
