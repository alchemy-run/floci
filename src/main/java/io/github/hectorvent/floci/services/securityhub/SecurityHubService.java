package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.securityhub.model.Hub;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubActionTarget;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubAutomationRule;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubFindingAggregator;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubInsight;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AWS Security Hub restJson1: hub enablement, custom action targets, insights,
 * automation rules, and the finding aggregator.
 *
 * <p>{@code DescribeHub} rejects an unsubscribed account with
 * {@code InvalidAccessException} (401). Sub-resources require the hub.
 */
@ApplicationScoped
public class SecurityHubService implements Resettable, TagHandler {

    static final String SERVICE = "securityhub";
    private static final String NOT_SUBSCRIBED = "Account is not subscribed to AWS Security Hub.";
    private static final String ALREADY_SUBSCRIBED = "Account is already subscribed to AWS Security Hub.";
    private static final Set<String> REGION_LINKING_MODES = Set.of(
            "ALL_REGIONS", "ALL_REGIONS_EXCEPT_SPECIFIED", "SPECIFIED_REGIONS", "NO_REGIONS");
    private static final Set<String> RULE_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> CONTROL_GENERATORS = Set.of("STANDARD_CONTROL", "SECURITY_CONTROL");
    private static final Set<String> WORKFLOW_STATUSES =
            Set.of("NEW", "NOTIFIED", "RESOLVED", "SUPPRESSED");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record StandardDef(String name, String description, String arnSuffix, boolean enabledByDefault) {}

    record ControlDef(
            String id, String title, String description, String severity, String remediationUrl) {}

    private static final List<StandardDef> STANDARDS = List.of(
            new StandardDef(
                    "AWS Foundational Security Best Practices v1.0.0",
                    "The AWS Foundational Security Best Practices standard is a set of automated security checks.",
                    "standards/aws-foundational-security-best-practices/v/1.0.0",
                    true),
            new StandardDef(
                    "CIS AWS Foundations Benchmark v1.2.0",
                    "The Center for Internet Security AWS Foundations Benchmark v1.2.0.",
                    "ruleset/cis-aws-foundations-benchmark/v/1.2.0",
                    true),
            new StandardDef(
                    "PCI DSS v3.2.1",
                    "The Payment Card Industry Data Security Standard v3.2.1.",
                    "standards/pci-dss/v/3.2.1",
                    false),
            new StandardDef(
                    "NIST SP 800-53 Rev. 5",
                    "The NIST SP 800-53 Revision 5 standard.",
                    "standards/nist-800-53/v/5.0.0",
                    false));

    private static final List<ControlDef> CONTROLS = List.of(
            new ControlDef(
                    "IAM.1",
                    "IAM policies should not allow full \"*\" administrative privileges",
                    "IAM policies should not allow full administrative privileges.",
                    "HIGH",
                    "https://docs.aws.amazon.com/console/securityhub/IAM.1/remediation"),
            new ControlDef(
                    "IAM.2",
                    "IAM users should not have IAM policies attached",
                    "IAM users should inherit permissions from groups or roles.",
                    "LOW",
                    "https://docs.aws.amazon.com/console/securityhub/IAM.2/remediation"),
            new ControlDef(
                    "S3.1",
                    "S3 general purpose buckets should have block public access settings enabled",
                    "Amazon S3 Block Public Access should be enabled.",
                    "MEDIUM",
                    "https://docs.aws.amazon.com/console/securityhub/S3.1/remediation"),
            new ControlDef(
                    "EC2.1",
                    "EBS snapshots should not be publicly restorable",
                    "Amazon EBS snapshots should not be publicly restorable.",
                    "CRITICAL",
                    "https://docs.aws.amazon.com/console/securityhub/EC2.1/remediation"));

    private final StorageBackend<String, Hub> hubs;
    private final StorageBackend<String, SecurityHubActionTarget> actionTargets;
    private final StorageBackend<String, SecurityHubInsight> insights;
    private final StorageBackend<String, SecurityHubAutomationRule> rules;
    private final StorageBackend<String, SecurityHubFindingAggregator> aggregators;
    private final RegionResolver regionResolver;

    @Inject
    public SecurityHubService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create(
                        SERVICE, "securityhub-hubs.json", new TypeReference<Map<String, Hub>>() {}),
                storageFactory.create(
                        SERVICE,
                        "securityhub-action-targets.json",
                        new TypeReference<Map<String, SecurityHubActionTarget>>() {}),
                storageFactory.create(
                        SERVICE,
                        "securityhub-insights.json",
                        new TypeReference<Map<String, SecurityHubInsight>>() {}),
                storageFactory.create(
                        SERVICE,
                        "securityhub-automation-rules.json",
                        new TypeReference<Map<String, SecurityHubAutomationRule>>() {}),
                storageFactory.create(
                        SERVICE,
                        "securityhub-finding-aggregators.json",
                        new TypeReference<Map<String, SecurityHubFindingAggregator>>() {}),
                regionResolver);
    }

    SecurityHubService(
            StorageBackend<String, Hub> hubs,
            StorageBackend<String, SecurityHubActionTarget> actionTargets,
            StorageBackend<String, SecurityHubInsight> insights,
            StorageBackend<String, SecurityHubAutomationRule> rules,
            StorageBackend<String, SecurityHubFindingAggregator> aggregators,
            RegionResolver regionResolver) {
        this.hubs = hubs;
        this.actionTargets = actionTargets;
        this.insights = insights;
        this.rules = rules;
        this.aggregators = aggregators;
        this.regionResolver = regionResolver;
    }

    public Hub describeHub(String region) {
        return requireHub(region);
    }

    public synchronized Hub enableSecurityHub(String region, JsonNode request) {
        requireObject(request);
        String account = account();
        String key = sessionKey(account, region);
        if (hubs.get(key).isPresent()) {
            throw conflict(ALREADY_SUBSCRIBED);
        }
        String generator = textOrNull(request, "ControlFindingGenerator");
        if (generator == null) {
            generator = "SECURITY_CONTROL";
        }
        requireControlGenerator(generator);
        String now = now();
        Hub hub = new Hub();
        hub.setAccountId(account);
        hub.setRegion(region);
        hub.setHubArn(hubArn(region, account));
        hub.setSubscribedAt(now);
        hub.setAutoEnableControls(true);
        hub.setControlFindingGenerator(generator);
        hub.setTags(readTags(field(request, "Tags")));
        hub.getProductSubscriptions().add(defaultProductArn(region, account));
        if (booleanOrDefault(request, "EnableDefaultStandards", true)) {
            for (StandardDef standard : STANDARDS) {
                if (standard.enabledByDefault()) {
                    hub.getStandardsSubscriptions().add(subscription(region, account, standard));
                }
            }
        }
        hubs.put(key, hub);
        return hub;
    }

    public synchronized void updateSecurityHubConfiguration(String region, JsonNode request) {
        requireObject(request);
        Hub hub = requireHub(region);
        JsonNode auto = field(request, "AutoEnableControls");
        if (auto != null && auto.isBoolean()) {
            hub.setAutoEnableControls(auto.asBoolean());
        }
        String generator = textOrNull(request, "ControlFindingGenerator");
        if (generator != null) {
            requireControlGenerator(generator);
            hub.setControlFindingGenerator(generator);
        }
        hubs.put(sessionKey(hub.getAccountId(), region), hub);
    }

    public synchronized void disableSecurityHub(String region) {
        Hub hub = requireHub(region);
        String account = hub.getAccountId();
        hubs.delete(sessionKey(account, region));
        for (SecurityHubActionTarget target : new ArrayList<>(actionTargets.values())) {
            if (ownedBy(target.getAccountId(), target.getRegion(), account, region)) {
                actionTargets.delete(target.getActionTargetArn());
            }
        }
        for (SecurityHubInsight insight : new ArrayList<>(insights.values())) {
            if (ownedBy(insight.getAccountId(), insight.getRegion(), account, region)) {
                insights.delete(insight.getInsightArn());
            }
        }
        for (SecurityHubAutomationRule rule : new ArrayList<>(rules.values())) {
            if (ownedBy(rule.getAccountId(), rule.getRegion(), account, region)) {
                rules.delete(rule.getRuleArn());
            }
        }
        for (SecurityHubFindingAggregator aggregator : new ArrayList<>(aggregators.values())) {
            if (ownedBy(aggregator.getAccountId(), aggregator.getRegion(), account, region)) {
                aggregators.delete(aggregator.getFindingAggregatorArn());
            }
        }
    }

    public synchronized SecurityHubActionTarget createActionTarget(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        String id = requireText(request, "Id");
        String name = requireText(request, "Name");
        String description = requireText(request, "Description");
        String account = account();
        String arn = resourceArn(region, account, "action/custom/" + id);
        if (actionTargets.get(arn).isPresent()) {
            throw conflict("Action target " + id + " already exists.");
        }
        SecurityHubActionTarget target = new SecurityHubActionTarget();
        target.setAccountId(account);
        target.setRegion(region);
        target.setId(id);
        target.setName(name);
        target.setDescription(description);
        target.setActionTargetArn(arn);
        actionTargets.put(arn, target);
        return target;
    }

    public List<SecurityHubActionTarget> describeActionTargets(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        List<String> arns = readStringList(field(request, "ActionTargetArns"));
        String account = account();
        if (arns.isEmpty()) {
            List<SecurityHubActionTarget> out = new ArrayList<>();
            for (SecurityHubActionTarget target : actionTargets.values()) {
                if (ownedBy(target.getAccountId(), target.getRegion(), account, region)) {
                    out.add(target);
                }
            }
            return out;
        }
        List<SecurityHubActionTarget> out = new ArrayList<>();
        for (String arn : arns) {
            out.add(requireActionTarget(region, arn));
        }
        return out;
    }

    public synchronized SecurityHubActionTarget updateActionTarget(String region, String arn, JsonNode request) {
        requireHub(region);
        requireObject(request);
        SecurityHubActionTarget target = requireActionTarget(region, arn);
        String name = textOrNull(request, "Name");
        if (name != null) {
            target.setName(name);
        }
        String description = textOrNull(request, "Description");
        if (description != null) {
            target.setDescription(description);
        }
        actionTargets.put(target.getActionTargetArn(), target);
        return target;
    }

    public synchronized String deleteActionTarget(String region, String arn) {
        requireHub(region);
        SecurityHubActionTarget target = requireActionTarget(region, arn);
        actionTargets.delete(target.getActionTargetArn());
        return target.getActionTargetArn();
    }

    public synchronized SecurityHubInsight createInsight(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        String name = requireText(request, "Name");
        String groupBy = requireText(request, "GroupByAttribute");
        Map<String, Object> filters = readObjectMap(field(request, "Filters"), "Filters");
        String account = account();
        String arn = resourceArn(region, account, "insight/" + UUID.randomUUID());
        SecurityHubInsight insight = new SecurityHubInsight();
        insight.setAccountId(account);
        insight.setRegion(region);
        insight.setInsightArn(arn);
        insight.setName(name);
        insight.setGroupByAttribute(groupBy);
        insight.setFilters(filters);
        insights.put(arn, insight);
        return insight;
    }

    public List<SecurityHubInsight> getInsights(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        List<String> arns = readStringList(field(request, "InsightArns"));
        String account = account();
        if (arns.isEmpty()) {
            List<SecurityHubInsight> out = new ArrayList<>();
            for (SecurityHubInsight insight : insights.values()) {
                if (ownedBy(insight.getAccountId(), insight.getRegion(), account, region)) {
                    out.add(insight);
                }
            }
            return out;
        }
        List<SecurityHubInsight> out = new ArrayList<>();
        for (String arn : arns) {
            out.add(requireInsight(region, arn));
        }
        return out;
    }

    public synchronized SecurityHubInsight updateInsight(String region, String arn, JsonNode request) {
        requireHub(region);
        requireObject(request);
        SecurityHubInsight insight = requireInsight(region, arn);
        String name = textOrNull(request, "Name");
        if (name != null) {
            insight.setName(name);
        }
        String groupBy = textOrNull(request, "GroupByAttribute");
        if (groupBy != null) {
            insight.setGroupByAttribute(groupBy);
        }
        JsonNode filters = field(request, "Filters");
        if (filters != null) {
            insight.setFilters(readObjectMap(filters, "Filters"));
        }
        insights.put(insight.getInsightArn(), insight);
        return insight;
    }

    public synchronized String deleteInsight(String region, String arn) {
        requireHub(region);
        SecurityHubInsight insight = requireInsight(region, arn);
        insights.delete(insight.getInsightArn());
        return insight.getInsightArn();
    }

    public synchronized SecurityHubAutomationRule createAutomationRule(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        String name = requireText(request, "RuleName");
        String description = requireText(request, "Description");
        int order = requireInt(request, "RuleOrder");
        String status = textOrNull(request, "RuleStatus");
        if (status == null) {
            status = "ENABLED";
        }
        requireRuleStatus(status);
        boolean terminal = booleanOrDefault(request, "IsTerminal", false);
        Map<String, Object> criteria = readObjectMap(field(request, "Criteria"), "Criteria");
        List<Map<String, Object>> actions = readObjectList(field(request, "Actions"), "Actions");
        String account = account();
        String now = now();
        String arn = resourceArn(region, account, "automation-rule/" + UUID.randomUUID());
        SecurityHubAutomationRule rule = new SecurityHubAutomationRule();
        rule.setAccountId(account);
        rule.setRegion(region);
        rule.setRuleArn(arn);
        rule.setRuleName(name);
        rule.setDescription(description);
        rule.setRuleOrder(order);
        rule.setRuleStatus(status);
        rule.setTerminal(terminal);
        rule.setCriteria(criteria);
        rule.setActions(actions);
        rule.setTags(readTags(field(request, "Tags")));
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        rule.setCreatedBy(account);
        rules.put(arn, rule);
        return rule;
    }

    public BatchRulesResult batchGetAutomationRules(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        List<String> arns = readStringList(field(request, "AutomationRulesArns"));
        List<SecurityHubAutomationRule> found = new ArrayList<>();
        List<UnprocessedRule> unprocessed = new ArrayList<>();
        for (String arn : arns) {
            Optional<SecurityHubAutomationRule> rule = findRule(region, arn);
            if (rule.isPresent()) {
                found.add(rule.get());
            } else {
                unprocessed.add(new UnprocessedRule(decodeArn(arn), 404, "Resource not found"));
            }
        }
        return new BatchRulesResult(found, List.of(), unprocessed);
    }

    public synchronized BatchRulesResult batchUpdateAutomationRules(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        JsonNode items = field(request, "UpdateAutomationRulesRequestItems");
        List<String> processed = new ArrayList<>();
        List<UnprocessedRule> unprocessed = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String arn = textOrNull(item, "RuleArn");
                if (arn == null) {
                    continue;
                }
                Optional<SecurityHubAutomationRule> existing = findRule(region, arn);
                if (existing.isEmpty()) {
                    unprocessed.add(new UnprocessedRule(decodeArn(arn), 404, "Resource not found"));
                    continue;
                }
                SecurityHubAutomationRule rule = existing.get();
                String name = textOrNull(item, "RuleName");
                if (name != null) {
                    rule.setRuleName(name);
                }
                String description = textOrNull(item, "Description");
                if (description != null) {
                    rule.setDescription(description);
                }
                JsonNode order = field(item, "RuleOrder");
                if (order != null && order.isNumber()) {
                    rule.setRuleOrder(order.asInt());
                }
                String status = textOrNull(item, "RuleStatus");
                if (status != null) {
                    requireRuleStatus(status);
                    rule.setRuleStatus(status);
                }
                JsonNode terminal = field(item, "IsTerminal");
                if (terminal != null && terminal.isBoolean()) {
                    rule.setTerminal(terminal.asBoolean());
                }
                JsonNode criteria = field(item, "Criteria");
                if (criteria != null) {
                    rule.setCriteria(readObjectMap(criteria, "Criteria"));
                }
                JsonNode actions = field(item, "Actions");
                if (actions != null) {
                    rule.setActions(readObjectList(actions, "Actions"));
                }
                rule.setUpdatedAt(now());
                rules.put(rule.getRuleArn(), rule);
                processed.add(rule.getRuleArn());
            }
        }
        return new BatchRulesResult(List.of(), processed, unprocessed);
    }

    public synchronized BatchRulesResult batchDeleteAutomationRules(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        List<String> arns = readStringList(field(request, "AutomationRulesArns"));
        List<String> processed = new ArrayList<>();
        List<UnprocessedRule> unprocessed = new ArrayList<>();
        for (String arn : arns) {
            Optional<SecurityHubAutomationRule> existing = findRule(region, arn);
            if (existing.isEmpty()) {
                unprocessed.add(new UnprocessedRule(decodeArn(arn), 404, "Resource not found"));
                continue;
            }
            rules.delete(existing.get().getRuleArn());
            processed.add(existing.get().getRuleArn());
        }
        return new BatchRulesResult(List.of(), processed, unprocessed);
    }

    public List<SecurityHubAutomationRule> listAutomationRules(String region) {
        requireHub(region);
        String account = account();
        List<SecurityHubAutomationRule> out = new ArrayList<>();
        for (SecurityHubAutomationRule rule : rules.values()) {
            if (ownedBy(rule.getAccountId(), rule.getRegion(), account, region)) {
                out.add(rule);
            }
        }
        return out;
    }

    public synchronized SecurityHubFindingAggregator createFindingAggregator(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        String account = account();
        for (SecurityHubFindingAggregator existing : aggregators.values()) {
            if (ownedBy(existing.getAccountId(), existing.getRegion(), account, region)) {
                throw conflict("A finding aggregator already exists for this account.");
            }
        }
        String mode = requireText(request, "RegionLinkingMode");
        requireRegionLinkingMode(mode);
        List<String> regions = sorted(readStringList(field(request, "Regions")));
        String arn = resourceArn(region, account, "finding-aggregator/" + UUID.randomUUID());
        SecurityHubFindingAggregator aggregator = new SecurityHubFindingAggregator();
        aggregator.setAccountId(account);
        aggregator.setRegion(region);
        aggregator.setFindingAggregatorArn(arn);
        aggregator.setFindingAggregationRegion(region);
        aggregator.setRegionLinkingMode(mode);
        aggregator.setRegions(regions);
        aggregators.put(arn, aggregator);
        return aggregator;
    }

    public SecurityHubFindingAggregator getFindingAggregator(String region, String arn) {
        requireHub(region);
        return requireAggregator(region, arn);
    }

    public List<SecurityHubFindingAggregator> listFindingAggregators(String region) {
        requireHub(region);
        String account = account();
        List<SecurityHubFindingAggregator> out = new ArrayList<>();
        for (SecurityHubFindingAggregator aggregator : aggregators.values()) {
            if (ownedBy(aggregator.getAccountId(), aggregator.getRegion(), account, region)) {
                out.add(aggregator);
            }
        }
        return out;
    }

    public synchronized SecurityHubFindingAggregator updateFindingAggregator(String region, JsonNode request) {
        requireHub(region);
        requireObject(request);
        String arn = requireText(request, "FindingAggregatorArn");
        SecurityHubFindingAggregator aggregator = requireAggregator(region, arn);
        String mode = textOrNull(request, "RegionLinkingMode");
        if (mode != null) {
            requireRegionLinkingMode(mode);
            aggregator.setRegionLinkingMode(mode);
        }
        JsonNode regions = field(request, "Regions");
        if (regions != null) {
            aggregator.setRegions(sorted(readStringList(regions)));
        }
        aggregators.put(aggregator.getFindingAggregatorArn(), aggregator);
        return aggregator;
    }

    public synchronized void deleteFindingAggregator(String region, String arn) {
        requireHub(region);
        SecurityHubFindingAggregator aggregator = requireAggregator(region, arn);
        aggregators.delete(aggregator.getFindingAggregatorArn());
    }

    public synchronized ObjectNode batchImportFindings(String region, JsonNode request) {
        Hub hub = requireHub(region);
        requireObject(request);
        JsonNode findings = field(request, "Findings");
        int success = 0;
        int failed = 0;
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode failedFindings = response.putArray("FailedFindings");
        if (findings != null && findings.isArray()) {
            for (JsonNode finding : findings) {
                String id = textOrNull(finding, "Id");
                if (id == null) {
                    failed++;
                    failedFindings.add(importError("", "InvalidInputException", "Finding Id is required."));
                    continue;
                }
                Map<String, Object> stored =
                        MAPPER.convertValue(finding, new TypeReference<LinkedHashMap<String, Object>>() {});
                hub.getFindings().put(id, stored);
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("Name", "FINDING_CREATED");
                record.put("FindingCreated", true);
                hub.getHistory().computeIfAbsent(id, key -> new ArrayList<>()).add(record);
                success++;
            }
        }
        hubs.put(sessionKey(hub.getAccountId(), region), hub);
        response.put("FailedCount", failed);
        response.put("SuccessCount", success);
        return response;
    }

    public ObjectNode getFindings(String region, JsonNode request) {
        Hub hub = requireHub(region);
        requireObject(request);
        JsonNode filters = field(request, "Filters");
        JsonNode max = field(request, "MaxResults");
        int limit = max != null && max.isNumber() ? Math.max(max.asInt(), 1) : Integer.MAX_VALUE;
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode list = response.putArray("Findings");
        int remaining = limit;
        for (Map<String, Object> finding : hub.getFindings().values()) {
            if (!matchesFilters(finding, filters)) {
                continue;
            }
            if (remaining-- <= 0) {
                break;
            }
            list.add(MAPPER.valueToTree(finding));
        }
        return response;
    }

    public synchronized ObjectNode batchUpdateFindings(String region, JsonNode request) {
        Hub hub = requireHub(region);
        requireObject(request);
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode processed = response.putArray("ProcessedFindings");
        ArrayNode unprocessed = response.putArray("UnprocessedFindings");
        JsonNode identifiers = field(request, "FindingIdentifiers");
        JsonNode workflow = field(request, "Workflow");
        JsonNode note = field(request, "Note");
        if (identifiers != null && identifiers.isArray()) {
            for (JsonNode identifier : identifiers) {
                String id = textOrNull(identifier, "Id");
                String productArn = textOrNull(identifier, "ProductArn");
                Map<String, Object> finding = id == null ? null : hub.getFindings().get(id);
                if (finding == null) {
                    ObjectNode missed = unprocessed.addObject();
                    missed.put("Id", id);
                    missed.put("ProductArn", productArn);
                    missed.put("ErrorCode", "ResourceNotFoundException");
                    missed.put("ErrorMessage", "Finding was not found.");
                    continue;
                }
                if (workflow != null) {
                    finding.put("Workflow", MAPPER.convertValue(
                            workflow, new TypeReference<LinkedHashMap<String, Object>>() {}));
                }
                if (note != null) {
                    finding.put("Note", MAPPER.convertValue(
                            note, new TypeReference<LinkedHashMap<String, Object>>() {}));
                }
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("Name", "FINDING_UPDATED");
                hub.getHistory().computeIfAbsent(id, key -> new ArrayList<>()).add(record);
                ObjectNode ok = processed.addObject();
                ok.put("Id", id);
                ok.put("ProductArn", productArn);
            }
        }
        hubs.put(sessionKey(hub.getAccountId(), region), hub);
        return response;
    }

    public ObjectNode getFindingHistory(String region, JsonNode request) {
        Hub hub = requireHub(region);
        requireObject(request);
        JsonNode identifier = field(request, "FindingIdentifier");
        String id = identifier == null ? null : textOrNull(identifier, "Id");
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode records = response.putArray("Records");
        if (id != null) {
            for (Map<String, Object> record : hub.getHistory().getOrDefault(id, List.of())) {
                records.add(MAPPER.valueToTree(record));
            }
        }
        return response;
    }

    public ObjectNode describeStandards(String region) {
        requireHub(region);
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode standards = response.putArray("Standards");
        for (StandardDef standard : STANDARDS) {
            ObjectNode node = standards.addObject();
            node.put("StandardsArn", standardArn(region, standard));
            node.put("Name", standard.name());
            node.put("Description", standard.description());
            node.put("EnabledByDefault", standard.enabledByDefault());
        }
        return response;
    }

    public ObjectNode getEnabledStandards(String region) {
        Hub hub = requireHub(region);
        ObjectNode response = MAPPER.createObjectNode();
        response.set("StandardsSubscriptions", MAPPER.valueToTree(hub.getStandardsSubscriptions()));
        return response;
    }

    public ObjectNode listSecurityControlDefinitions(int limit) {
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode list = response.putArray("SecurityControlDefinitions");
        int remaining = Math.max(limit, 1);
        for (ControlDef control : CONTROLS) {
            if (remaining-- <= 0) {
                break;
            }
            list.add(toControl(control));
        }
        return response;
    }

    public ObjectNode getSecurityControlDefinition(String securityControlId) {
        if (securityControlId == null || securityControlId.isBlank()) {
            throw invalidInput("SecurityControlId is a required parameter.");
        }
        for (ControlDef control : CONTROLS) {
            if (control.id().equals(securityControlId)) {
                ObjectNode response = MAPPER.createObjectNode();
                response.set("SecurityControlDefinition", toControl(control));
                return response;
            }
        }
        throw notFound("Security control " + securityControlId + " was not found.");
    }

    public ObjectNode describeProducts(String region) {
        String accountId = account();
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode products = response.putArray("Products");
        ObjectNode product = products.addObject();
        product.put("ProductArn", defaultProductArn(region, accountId));
        product.put("ProductName", "Default");
        product.put("CompanyName", "AWS");
        product.put("Description", "Default product for custom findings.");
        ObjectNode securityHub = products.addObject();
        securityHub.put("ProductArn", "arn:aws:securityhub:" + region + "::product/aws/securityhub");
        securityHub.put("ProductName", "Security Hub");
        securityHub.put("CompanyName", "AWS");
        ObjectNode guardDuty = products.addObject();
        guardDuty.put("ProductArn", "arn:aws:securityhub:" + region + "::product/aws/guardduty");
        guardDuty.put("ProductName", "GuardDuty");
        guardDuty.put("CompanyName", "AWS");
        return response;
    }

    public ObjectNode listEnabledProductsForImport(String region) {
        Hub hub = requireHub(region);
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode list = response.putArray("ProductSubscriptions");
        for (String arn : hub.getProductSubscriptions()) {
            list.add(arn);
        }
        return response;
    }

    public ObjectNode emptyList(String region, String field) {
        requireHub(region);
        ObjectNode response = MAPPER.createObjectNode();
        response.putArray(field);
        return response;
    }

    public ObjectNode getAdministratorAccount(String region) {
        requireHub(region);
        return MAPPER.createObjectNode();
    }

    public ObjectNode getInvitationsCount(String region) {
        requireHub(region);
        ObjectNode response = MAPPER.createObjectNode();
        response.put("InvitationsCount", 0);
        return response;
    }

    public ObjectNode listOrganizationAdminAccounts(String region) {
        requireHub(region);
        ObjectNode response = MAPPER.createObjectNode();
        response.putArray("AdminAccounts");
        return response;
    }

    public ObjectNode describeOrganizationConfiguration(String region) {
        requireHub(region);
        ObjectNode response = MAPPER.createObjectNode();
        response.put("AutoEnable", false);
        return response;
    }

    public String account() {
        return regionResolver.getAccountId();
    }

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(requireTagged(region, arn).tags());
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        Tagged tagged = requireTagged(region, arn);
        if (tags != null) {
            tagged.tags().putAll(tags);
        }
        persistTagged(tagged);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        Tagged tagged = requireTagged(region, arn);
        if (tagKeys != null) {
            tagKeys.forEach(tagged.tags()::remove);
        }
        persistTagged(tagged);
    }

    @Override
    public void clear() {
        hubs.clear();
        actionTargets.clear();
        insights.clear();
        rules.clear();
        aggregators.clear();
    }

    private Hub requireHub(String region) {
        return hubs.get(sessionKey(account(), region)).orElseThrow(SecurityHubService::invalidAccess);
    }

    private SecurityHubActionTarget requireActionTarget(String region, String arn) {
        String decoded = decodeArn(arn);
        SecurityHubActionTarget target = actionTargets.get(decoded).orElse(null);
        if (target == null || !ownedBy(target.getAccountId(), target.getRegion(), account(), region)) {
            throw notFound("Action target " + decoded + " was not found.");
        }
        return target;
    }

    private SecurityHubInsight requireInsight(String region, String arn) {
        String decoded = decodeArn(arn);
        SecurityHubInsight insight = insights.get(decoded).orElse(null);
        if (insight == null || !ownedBy(insight.getAccountId(), insight.getRegion(), account(), region)) {
            throw invalidInput("Insight " + decoded + " was not found.");
        }
        return insight;
    }

    private Optional<SecurityHubAutomationRule> findRule(String region, String arn) {
        String decoded = decodeArn(arn);
        SecurityHubAutomationRule rule = rules.get(decoded).orElse(null);
        if (rule == null || !ownedBy(rule.getAccountId(), rule.getRegion(), account(), region)) {
            return Optional.empty();
        }
        return Optional.of(rule);
    }

    private SecurityHubFindingAggregator requireAggregator(String region, String arn) {
        String decoded = decodeArn(arn);
        SecurityHubFindingAggregator aggregator = aggregators.get(decoded).orElse(null);
        if (aggregator == null || !ownedBy(aggregator.getAccountId(), aggregator.getRegion(), account(), region)) {
            throw notFound("Finding aggregator " + decoded + " was not found.");
        }
        return aggregator;
    }

    private Tagged requireTagged(String region, String arn) {
        String decoded = decodeArn(arn);
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(decoded);
        } catch (IllegalArgumentException e) {
            throw invalidInput("Invalid resource ARN: " + decoded);
        }
        if (!SERVICE.equals(parsed.service())) {
            throw notFound("Resource " + decoded + " was not found.");
        }
        requireHub(region);
        String resource = parsed.resource();
        if ("hub/default".equals(resource)) {
            Hub hub = requireHub(region);
            return new Tagged(hub, null, hub.getTags());
        }
        if (resource.startsWith("automation-rule/")) {
            SecurityHubAutomationRule rule = findRule(region, decoded)
                    .orElseThrow(() -> notFound("Resource " + decoded + " was not found."));
            return new Tagged(null, rule, rule.getTags());
        }
        throw notFound("Resource " + decoded + " was not found.");
    }

    private void persistTagged(Tagged tagged) {
        if (tagged.hub() != null) {
            hubs.put(sessionKey(tagged.hub().getAccountId(), tagged.hub().getRegion()), tagged.hub());
        } else if (tagged.rule() != null) {
            rules.put(tagged.rule().getRuleArn(), tagged.rule());
        }
    }

    private static List<ObjectNode> defaultControlDefinitions() {
        ObjectNode iam1 = MAPPER.createObjectNode();
        iam1.put("SecurityControlId", "IAM.1");
        iam1.put("Title", "IAM policies should not allow full '*' administrative privileges");
        iam1.put("SeverityRating", "HIGH");
        ObjectNode s3 = MAPPER.createObjectNode();
        s3.put("SecurityControlId", "S3.1");
        s3.put("Title", "S3 general purpose buckets should have block public access settings enabled");
        s3.put("SeverityRating", "MEDIUM");
        return List.of(iam1, s3);
    }

    private static String hubArn(String region, String account) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, "hub/default").toString();
    }

    private static String defaultProductArn(String region, String account) {
        return "arn:aws:securityhub:" + region + ":" + account + ":product/" + account + "/default";
    }

    private static String findingKey(String productArn, String id) {
        return productArn + "\0" + id;
    }

    private static Map<String, Object> subscription(String region, String account, StandardDef standard) {
        Map<String, Object> sub = new LinkedHashMap<>();
        String suffix = standard.arnSuffix()
                .replaceFirst("^standards/", "")
                .replaceFirst("^ruleset/", "");
        sub.put("StandardsSubscriptionArn",
                "arn:aws:securityhub:" + region + ":" + account + ":subscription/" + suffix);
        sub.put("StandardsArn", standardArn(region, standard));
        sub.put("StandardsInput", Map.of());
        sub.put("StandardsStatus", "READY");
        return sub;
    }

    private static String standardArn(String region, StandardDef standard) {
        if (standard.arnSuffix().startsWith("ruleset/")) {
            return "arn:aws:securityhub:::" + standard.arnSuffix();
        }
        return "arn:aws:securityhub:" + region + "::" + standard.arnSuffix();
    }

    private static ObjectNode toControl(ControlDef control) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("SecurityControlId", control.id());
        node.put("Title", control.title());
        node.put("Description", control.description());
        node.put("RemediationUrl", control.remediationUrl());
        node.put("SeverityRating", control.severity());
        node.put("CurrentRegionAvailability", "AVAILABLE");
        return node;
    }

    private static ObjectNode importError(String id, String code, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("Id", id);
        node.put("ErrorCode", code);
        node.put("ErrorMessage", message);
        return node;
    }

    private static Map<String, Object> fieldUpdate(String field, String oldValue, String newValue) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("UpdatedField", field);
        if (oldValue != null) {
            update.put("OldValue", oldValue);
        }
        update.put("NewValue", newValue);
        return update;
    }

    private static String nestedText(Object value, String field) {
        if (value instanceof Map<?, ?> map) {
            Object nested = map.get(field);
            return nested == null ? null : String.valueOf(nested);
        }
        return null;
    }

    private static boolean matchesFilters(Map<String, Object> finding, JsonNode filters) {
        if (filters == null || filters.isNull() || !filters.isObject() || filters.isEmpty()) {
            return true;
        }
        JsonNode idFilters = filters.get("Id");
        if (idFilters != null && idFilters.isArray() && !idFilters.isEmpty()) {
            Object rawId = finding.get("Id");
            String id = rawId == null ? null : String.valueOf(rawId);
            boolean matched = false;
            for (JsonNode filter : idFilters) {
                if (matchesStringFilter(id, filter)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesStringFilter(String value, JsonNode filter) {
        if (value == null || filter == null || !filter.isObject()) {
            return false;
        }
        String expected = textOrNull(filter, "Value");
        if (expected == null) {
            return false;
        }
        String comparison = textOrNull(filter, "Comparison");
        if (comparison == null || "EQUALS".equals(comparison)) {
            return value.equals(expected);
        }
        if ("PREFIX".equals(comparison)) {
            return value.startsWith(expected);
        }
        if ("NOT_EQUALS".equals(comparison)) {
            return !value.equals(expected);
        }
        return value.equals(expected);
    }

    private static String resourceArn(String region, String account, String resource) {
        return AwsArnUtils.Arn.of(SERVICE, region, account, resource).toString();
    }

    private static String sessionKey(String accountId, String region) {
        return accountId + ":" + region;
    }

    private static boolean ownedBy(String accountId, String resourceRegion, String account, String region) {
        return account.equals(accountId) && region.equals(resourceRegion);
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalidInput("Request body must be a JSON object.");
        }
    }

    private static JsonNode field(JsonNode node, String pascal) {
        if (node == null || pascal == null || pascal.isEmpty()) {
            return null;
        }
        JsonNode value = node.get(pascal);
        if (value != null && !value.isNull()) {
            return value;
        }
        String camel = Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
        value = node.get(camel);
        return value == null || value.isNull() ? null : value;
    }

    private static String textOrNull(JsonNode node, String pascal) {
        JsonNode value = field(node, pascal);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String requireText(JsonNode node, String pascal) {
        String value = textOrNull(node, pascal);
        if (value == null) {
            throw invalidInput(pascal + " is a required parameter.");
        }
        return value;
    }

    private static int requireInt(JsonNode node, String pascal) {
        JsonNode value = field(node, pascal);
        if (value == null || !value.isNumber()) {
            throw invalidInput(pascal + " is a required parameter.");
        }
        return value.asInt();
    }

    private static boolean booleanOrDefault(JsonNode node, String pascal, boolean fallback) {
        JsonNode value = field(node, pascal);
        if (value == null || !value.isBoolean()) {
            return fallback;
        }
        return value.asBoolean();
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode value : node) {
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static Map<String, Object> readObjectMap(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return new LinkedHashMap<>();
        }
        if (!node.isObject()) {
            throw invalidInput(field + " must be a JSON object.");
        }
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private static List<Map<String, Object>> readObjectList(JsonNode node, String field) {
        List<Map<String, Object>> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (!node.isArray()) {
            throw invalidInput(field + " must be a list.");
        }
        for (JsonNode value : node) {
            if (value != null && value.isObject()) {
                values.add(MAPPER.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}));
            }
        }
        return values;
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isObject()) {
            throw invalidInput("Tags must be a map.");
        }
        tagsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            tags.put(entry.getKey(), value == null || value.isNull() ? "" : value.asText());
        });
        return tags;
    }

    private static void requireControlGenerator(String generator) {
        if (!CONTROL_GENERATORS.contains(generator)) {
            throw invalidInput("ControlFindingGenerator is invalid.");
        }
    }

    private static void requireRuleStatus(String status) {
        if (!RULE_STATUSES.contains(status)) {
            throw invalidInput("RuleStatus is invalid.");
        }
    }

    private static void requireRegionLinkingMode(String mode) {
        if (!REGION_LINKING_MODES.contains(mode)) {
            throw invalidInput("RegionLinkingMode is invalid.");
        }
    }

    static String decodeArn(String arn) {
        if (arn == null || arn.isEmpty()) {
            return arn;
        }
        try {
            String decoded = arn;
            for (int i = 0; i < 2; i++) {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            return arn;
        }
    }

    static AwsException invalidAccess() {
        return new AwsException("InvalidAccessException", NOT_SUBSCRIBED, 401);
    }

    static AwsException conflict(String message) {
        return new AwsException("ResourceConflictException", message, 409);
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException invalidInput(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }

    record UnprocessedRule(String ruleArn, int errorCode, String errorMessage) {}

    record BatchRulesResult(
            List<SecurityHubAutomationRule> rules,
            List<String> processed,
            List<UnprocessedRule> unprocessed) {}

    private record Tagged(Hub hub, SecurityHubAutomationRule rule, Map<String, String> tags) {}
}
