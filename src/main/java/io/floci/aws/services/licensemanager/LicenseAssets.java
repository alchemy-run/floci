package io.floci.aws.services.licensemanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * License asset rulesets and the asset groups that apply them. A ruleset's rules match instances (from the resource
 * inventory), license configurations and received licenses by their attributes; an asset group lists the assets its
 * rulesets match. AWS-managed rulesets are listed on request.
 */
@ApplicationScoped
public class LicenseAssets {

    private final LicenseManagerStore store;
    private final LicenseConfigurations configurations;
    private final LicenseIssuance issuance;
    private final ObjectMapper mapper;

    @Inject
    public LicenseAssets(LicenseManagerStore store, LicenseConfigurations configurations, LicenseIssuance issuance) {
        this.store = store;
        this.configurations = configurations;
        this.issuance = issuance;
        this.mapper = store.mapper();
    }

    // ================================================================ rulesets

    public ObjectNode createLicenseAssetRuleset(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CreateLicenseAssetRuleset", in);
        if (repeated != null) {
            return repeated;
        }
        checkRules(in.path("Rules"));
        String id = "lar-" + LicenseManagerStore.hex(16);
        ObjectNode ruleset = store.record();
        ruleset.put("Name", in.path("Name").asText());
        LicenseConfigurations.copy(in, ruleset, "Description");
        ruleset.set("Rules", in.path("Rules").deepCopy());
        ruleset.put("LicenseAssetRulesetArn", store.regionalArn(region, "license-asset-ruleset:" + id));
        if (in.path("Tags").isArray()) {
            LicenseManagerStore.addTags(ruleset, in.get("Tags"));
        }
        store.put(region, LicenseManagerStore.RULESET, id, ruleset);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseAssetRulesetArn", ruleset.path("LicenseAssetRulesetArn").asText());
        store.remember("CreateLicenseAssetRuleset", in, out);
        return out;
    }

    private static void checkRules(JsonNode rules) {
        if (rules.isEmpty()) {
            throw LicenseManagerStore.validation("A ruleset needs at least one rule.");
        }
        for (JsonNode rule : rules) {
            JsonNode statement = rule.path("RuleStatement");
            int kinds = (statement.has("LicenseConfigurationRuleStatement") ? 1 : 0)
                    + (statement.has("LicenseRuleStatement") ? 1 : 0)
                    + (statement.has("InstanceRuleStatement") ? 1 : 0);
            if (kinds != 1) {
                throw LicenseManagerStore.validation("Each rule statement names one of license configurations, "
                        + "licenses or instances.");
            }
        }
    }

    private Optional<ObjectNode> ruleset(String arn) {
        Optional<AwsArnUtils.Arn> parsed = LicenseManagerStore.parse(arn);
        String resource = parsed.map(AwsArnUtils.Arn::resource).orElse("");
        if (!resource.startsWith("license-asset-ruleset:")) {
            return Optional.empty();
        }
        String id = resource.substring("license-asset-ruleset:".length());
        if (managed(parsed.get())) {
            return managed(parsed.get().region()).stream()
                    .filter(r -> arn.equals(r.path("LicenseAssetRulesetArn").asText())).findFirst();
        }
        return store.getForAccount(parsed.get().accountId(), parsed.get().region(), LicenseManagerStore.RULESET, id);
    }

    /** Whether an ARN names an AWS-managed ruleset, whose account is {@code aws}. */
    private static boolean managed(AwsArnUtils.Arn arn) {
        return "aws".equals(arn.accountId()); // partition-literal: the AWS-managed resources' account, not a partition
    }

    private ObjectNode existingRuleset(String arn) {
        return ruleset(arn).orElseThrow(() -> LicenseManagerStore.notFound("The license asset ruleset " + arn
                + " does not exist."));
    }

    /** The AWS-managed rulesets: Windows Server instances, and SQL Server licenses. */
    private List<ObjectNode> managed(String region) {
        List<ObjectNode> rulesets = new ArrayList<>();
        rulesets.add(managedRuleset(region, "lar-windows-server", "AWS-WindowsServerInstances",
                "Instances that run Windows Server.", "InstanceRuleStatement", "Platform", "Windows"));
        rulesets.add(managedRuleset(region, "lar-sql-server", "AWS-SQLServerLicenses",
                "Licenses for Microsoft SQL Server.", "LicenseRuleStatement", "ProductName", "SQL Server"));
        return rulesets;
    }

    private ObjectNode managedRuleset(String region, String id, String name, String description, String kind,
                                      String key, String value) {
        ObjectNode ruleset = mapper.createObjectNode();
        ruleset.put("Name", name);
        ruleset.put("Description", description);
        ObjectNode matching = ruleset.putArray("Rules").addObject().putObject("RuleStatement").putObject(kind)
                .putObject("MatchingRuleStatement");
        matching.put("KeyToMatch", key);
        matching.put("Constraint", "contains");
        matching.putArray("ValueToMatch").add(value);
        ruleset.put("LicenseAssetRulesetArn", AwsArnUtils.Arn.of(LicenseManagerStore.SERVICE, region, "aws",
                "license-asset-ruleset:" + id).toString());
        return ruleset;
    }

    public ObjectNode getLicenseAssetRuleset(JsonNode in, String region) {
        ObjectNode ruleset = existingRuleset(in.path("LicenseAssetRulesetArn").asText());
        ObjectNode out = mapper.createObjectNode();
        out.set("LicenseAssetRuleset", LicenseManagerStore.view(ruleset));
        return out;
    }

    public ObjectNode updateLicenseAssetRuleset(JsonNode in, String region) {
        String arn = in.path("LicenseAssetRulesetArn").asText();
        ObjectNode ruleset = existingRuleset(arn);
        if (LicenseManagerStore.parse(arn).map(LicenseAssets::managed).orElse(false)) {
            throw LicenseManagerStore.invalid("AWS-managed rulesets can't be changed.");
        }
        checkRules(in.path("Rules"));
        LicenseConfigurations.copy(in, ruleset, "Name", "Description");
        ruleset.set("Rules", in.path("Rules").deepCopy());
        store.put(region, LicenseManagerStore.RULESET, ruleset.path("_").path("id").asText(), ruleset);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseAssetRulesetArn", arn);
        return out;
    }

    public ObjectNode deleteLicenseAssetRuleset(JsonNode in, String region) {
        String arn = in.path("LicenseAssetRulesetArn").asText();
        ObjectNode ruleset = existingRuleset(arn);
        for (ObjectNode group : store.list(region, LicenseManagerStore.ASSET_GROUP)) {
            if (!"DELETED".equals(group.path("Status").asText()) && LicenseManagerStore.any(
                    group.path("AssociatedLicenseAssetRulesetARNs"), arn::equals)) {
                throw LicenseManagerStore.conflict("The ruleset is used by the asset group "
                        + group.path("Name").asText() + ".");
            }
        }
        store.delete(region, LicenseManagerStore.RULESET, ruleset.path("_").path("id").asText());
        return mapper.createObjectNode();
    }

    public ObjectNode listLicenseAssetRulesets(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        Map<String, Function<JsonNode, String>> fields = Map.of("Name", r -> r.path("Name").asText());
        List<ObjectNode> all = new ArrayList<>(store.list(region, LicenseManagerStore.RULESET));
        if (in.path("ShowAWSManagedLicenseAssetRulesets").asBoolean(false)) {
            all.addAll(managed(region));
        }
        for (ObjectNode ruleset : all) {
            ObjectNode view = LicenseManagerStore.view(ruleset);
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, view)) {
                items.add(view);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "LicenseAssetRulesets");
        return out;
    }

    // ================================================================ groups

    public ObjectNode createLicenseAssetGroup(JsonNode in, String region) {
        ObjectNode repeated = store.repeated("CreateLicenseAssetGroup", in);
        if (repeated != null) {
            return repeated;
        }
        in.path("AssociatedLicenseAssetRulesetARNs").forEach(arn -> existingRuleset(arn.asText()));
        String id = "lag-" + LicenseManagerStore.hex(16);
        ObjectNode group = store.record();
        group.put("Name", in.path("Name").asText());
        LicenseConfigurations.copy(in, group, "Description", "LicenseAssetGroupConfigurations",
                "AssociatedLicenseAssetRulesetARNs", "Properties");
        group.put("LicenseAssetGroupArn", store.regionalArn(region, "license-asset-group:" + id));
        group.put("Status", "ACTIVE");
        double now = LicenseManagerStore.epoch();
        group.put("LatestUsageAnalysisTime", now);
        group.put("LatestResourceDiscoveryTime", now);
        if (in.path("Tags").isArray()) {
            LicenseManagerStore.addTags(group, in.get("Tags"));
        }
        store.put(region, LicenseManagerStore.ASSET_GROUP, id, group);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseAssetGroupArn", group.path("LicenseAssetGroupArn").asText());
        out.put("Status", "ACTIVE");
        store.remember("CreateLicenseAssetGroup", in, out);
        return out;
    }

    private ObjectNode group(String arn) {
        Optional<AwsArnUtils.Arn> parsed = LicenseManagerStore.parse(arn);
        String resource = parsed.map(AwsArnUtils.Arn::resource).orElse("");
        if (!resource.startsWith("license-asset-group:")) {
            throw LicenseManagerStore.notFound("The license asset group " + arn + " does not exist.");
        }
        return store.getForAccount(parsed.get().accountId(), parsed.get().region(), LicenseManagerStore.ASSET_GROUP,
                        resource.substring("license-asset-group:".length()))
                .filter(g -> !"DELETED".equals(g.path("Status").asText()))
                .orElseThrow(() -> LicenseManagerStore.notFound("The license asset group " + arn
                        + " does not exist."));
    }

    public ObjectNode getLicenseAssetGroup(JsonNode in, String region) {
        ObjectNode out = mapper.createObjectNode();
        out.set("LicenseAssetGroup", LicenseManagerStore.view(group(in.path("LicenseAssetGroupArn").asText())));
        return out;
    }

    public ObjectNode updateLicenseAssetGroup(JsonNode in, String region) {
        ObjectNode group = group(in.path("LicenseAssetGroupArn").asText());
        in.path("AssociatedLicenseAssetRulesetARNs").forEach(arn -> existingRuleset(arn.asText()));
        LicenseConfigurations.copy(in, group, "Name", "Description", "LicenseAssetGroupConfigurations",
                "AssociatedLicenseAssetRulesetARNs", "Properties", "Status");
        store.put(region, LicenseManagerStore.ASSET_GROUP, group.path("_").path("id").asText(), group);
        ObjectNode out = mapper.createObjectNode();
        out.put("LicenseAssetGroupArn", group.path("LicenseAssetGroupArn").asText());
        out.put("Status", group.path("Status").asText());
        return out;
    }

    public ObjectNode deleteLicenseAssetGroup(JsonNode in, String region) {
        ObjectNode group = group(in.path("LicenseAssetGroupArn").asText());
        group.put("Status", "DELETED");
        store.put(region, LicenseManagerStore.ASSET_GROUP, group.path("_").path("id").asText(), group);
        ObjectNode out = mapper.createObjectNode();
        out.put("Status", "DELETED");
        return out;
    }

    public ObjectNode listLicenseAssetGroups(JsonNode in, String region) {
        List<ObjectNode> items = new ArrayList<>();
        Map<String, Function<JsonNode, String>> fields = Map.of(
                "Name", g -> g.path("Name").asText(),
                "Status", g -> g.path("Status").asText());
        for (ObjectNode group : store.list(region, LicenseManagerStore.ASSET_GROUP)) {
            if ("DELETED".equals(group.path("Status").asText())) {
                continue;
            }
            ObjectNode view = LicenseManagerStore.view(group);
            if (LicenseManagerStore.filtered(in.path("Filters"), fields, view)) {
                items.add(view);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(items, in, out, "LicenseAssetGroups");
        return out;
    }

    // ================================================================ assets

    public ObjectNode listAssetsForLicenseAssetGroup(JsonNode in, String region) {
        ObjectNode group = group(in.path("LicenseAssetGroupArn").asText());
        String type = in.path("AssetType").asText();
        String statementKind = switch (type) {
            case "Instance" -> "InstanceRuleStatement";
            case "License" -> "LicenseRuleStatement";
            case "LicenseConfiguration" -> "LicenseConfigurationRuleStatement";
            default -> throw LicenseManagerStore.validation("The asset type " + type + " is not Instance, License or "
                    + "LicenseConfiguration.");
        };
        List<JsonNode> statements = new ArrayList<>();
        for (JsonNode arn : group.path("AssociatedLicenseAssetRulesetARNs")) {
            ruleset(arn.asText()).ifPresent(ruleset -> ruleset.path("Rules").forEach(rule -> {
                JsonNode statement = rule.path("RuleStatement").path(statementKind);
                if (statement.isObject()) {
                    statements.add(statement);
                }
            }));
        }
        List<ObjectNode> candidates = new ArrayList<>();
        String arnField;
        switch (type) {
            case "Instance" -> {
                configurations.listResourceInventory(mapper.createObjectNode(), region)
                        .path("ResourceInventoryList").forEach(i -> candidates.add((ObjectNode) i));
                arnField = "ResourceArn";
            }
            case "License" -> {
                issuance.listReceivedLicenses(mapper.createObjectNode(), region).path("Licenses")
                        .forEach(l -> candidates.add((ObjectNode) l));
                arnField = "LicenseArn";
            }
            default -> {
                configurations.listLicenseConfigurations(mapper.createObjectNode(), region)
                        .path("LicenseConfigurations").forEach(c -> candidates.add((ObjectNode) c));
                arnField = "LicenseConfigurationArn";
            }
        }
        List<ObjectNode> assets = new ArrayList<>();
        for (ObjectNode candidate : candidates) {
            boolean match = false;
            for (JsonNode statement : statements) {
                match |= matches(statement, candidate);
            }
            if (match) {
                ObjectNode asset = mapper.createObjectNode();
                asset.put("AssetArn", candidate.path(arnField).asText());
                asset.put("LatestAssetDiscoveryTime", LicenseManagerStore.epoch());
                assets.add(asset);
            }
        }
        ObjectNode out = mapper.createObjectNode();
        LicenseManagerStore.page(assets, in, out, "Assets");
        return out;
    }

    /** Whether an asset satisfies a rule statement: its matching statement, or all (and) or any (or) of several. */
    static boolean matches(JsonNode statement, JsonNode asset) {
        if (statement.has("MatchingRuleStatement")) {
            return matching(statement.get("MatchingRuleStatement"), asset);
        }
        if (statement.has("ScriptRuleStatement")) {
            return true;
        }
        if (statement.has("AndRuleStatement")) {
            boolean all = true;
            for (JsonNode matching : statement.path("AndRuleStatement").path("MatchingRuleStatements")) {
                all &= matching(matching, asset);
            }
            return all;
        }
        if (statement.has("OrRuleStatement")) {
            for (JsonNode matching : statement.path("OrRuleStatement").path("MatchingRuleStatements")) {
                if (matching(matching, asset)) {
                    return true;
                }
            }
            return statement.path("OrRuleStatement").path("ScriptRuleStatements").size() > 0;
        }
        return false;
    }

    private static boolean matching(JsonNode matching, JsonNode asset) {
        String key = matching.path("KeyToMatch").asText();
        String value = value(asset, key).toLowerCase(Locale.ROOT);
        String constraint = matching.path("Constraint").asText("equals").toLowerCase(Locale.ROOT);
        for (JsonNode wanted : matching.path("ValueToMatch")) {
            String w = wanted.asText().toLowerCase(Locale.ROOT);
            boolean hit = switch (constraint) {
                case "contains" -> value.contains(w);
                case "beginswith", "begins_with" -> value.startsWith(w);
                case "notequals", "not_equals" -> !value.equals(w);
                default -> value.equals(w);
            };
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private static String value(JsonNode asset, String key) {
        JsonNode direct = asset.get(key);
        if (direct != null && direct.isValueNode()) {
            return direct.asText();
        }
        if ("IssuerName".equals(key)) {
            return asset.path("Issuer").path("Name").asText();
        }
        for (JsonNode tag : asset.path("Tags")) {
            if (key.equals("tag:" + tag.path("Key").asText())) {
                return tag.path("Value").asText();
            }
        }
        return "";
    }
}
