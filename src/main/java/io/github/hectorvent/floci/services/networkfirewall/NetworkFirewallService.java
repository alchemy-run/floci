package io.github.hectorvent.floci.services.networkfirewall;

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
import io.github.hectorvent.floci.services.networkfirewall.model.NetworkFirewall;
import io.github.hectorvent.floci.services.networkfirewall.model.NetworkFirewallAnalysisReport;
import io.github.hectorvent.floci.services.networkfirewall.model.NetworkFirewallFlowOperation;
import io.github.hectorvent.floci.services.networkfirewall.model.NetworkFirewallPolicy;
import io.github.hectorvent.floci.services.networkfirewall.model.NetworkFirewallSubnetMapping;
import io.github.hectorvent.floci.services.networkfirewall.model.RuleGroupRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network Firewall JSON 1.0 ({@code NetworkFirewall_20201112.*}).
 *
 * <p>Firewalls become {@code READY} immediately so Alchemy wait-loops do not
 * stall. Flow captures/flushes complete on start. StartAnalysisReport rejects
 * until analysis types are enabled on the firewall.
 */
@ApplicationScoped
public class NetworkFirewallService implements Resettable {

    static final String SERVICE = "network-firewall";
    static final String TARGET_PREFIX = "NetworkFirewall_20201112.";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final Pattern SID = Pattern.compile("(?i)\\bsid\\s*:\\s*(\\d+)");
    private static final Pattern MSG = Pattern.compile("(?i)\\bmsg\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern METADATA = Pattern.compile("(?i)\\bmetadata\\s*:\\s*([^;]+)");

    private final StorageBackend<String, NetworkFirewallPolicy> policies;
    private final StorageBackend<String, NetworkFirewall> firewalls;
    private final StorageBackend<String, NetworkFirewallFlowOperation> flowOperations;
    private final StorageBackend<String, NetworkFirewallAnalysisReport> analysisReports;
    private final StorageBackend<String, RuleGroupRecord> ruleGroups;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public NetworkFirewallService(StorageFactory factory, Ec2Service ec2Service,
                                  RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(factory.create(SERVICE, "network-firewall-policies.json",
                        new TypeReference<Map<String, NetworkFirewallPolicy>>() {
                        }),
                factory.create(SERVICE, "network-firewall-firewalls.json",
                        new TypeReference<Map<String, NetworkFirewall>>() {
                        }),
                factory.create(SERVICE, "network-firewall-flow-operations.json",
                        new TypeReference<Map<String, NetworkFirewallFlowOperation>>() {
                        }),
                factory.create(SERVICE, "network-firewall-analysis-reports.json",
                        new TypeReference<Map<String, NetworkFirewallAnalysisReport>>() {
                        }),
                factory.create(SERVICE, "network-firewall-rule-groups.json",
                        new TypeReference<Map<String, RuleGroupRecord>>() {
                        }),
                ec2Service, regionResolver, objectMapper);
    }

    NetworkFirewallService(StorageBackend<String, NetworkFirewallPolicy> policies,
                           StorageBackend<String, NetworkFirewall> firewalls,
                           StorageBackend<String, NetworkFirewallFlowOperation> flowOperations,
                           StorageBackend<String, NetworkFirewallAnalysisReport> analysisReports,
                           StorageBackend<String, RuleGroupRecord> ruleGroups,
                           Ec2Service ec2Service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.policies = policies;
        this.firewalls = firewalls;
        this.flowOperations = flowOperations;
        this.analysisReports = analysisReports;
        this.ruleGroups = ruleGroups;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        policies.clear();
        firewalls.clear();
        flowOperations.clear();
        analysisReports.clear();
        ruleGroups.clear();
    }

    public synchronized ObjectNode createFirewallPolicy(JsonNode request, String region) {
        String name = requireText(request, "FirewallPolicyName");
        JsonNode definition = request.get("FirewallPolicy");
        if (definition == null || !definition.isObject()) {
            throw invalidRequest("FirewallPolicy is a required parameter.");
        }
        if (findPolicyByName(region, name).isPresent()) {
            throw invalidRequest("A resource with the specified name, " + name + ", already exists.");
        }
        NetworkFirewallPolicy policy = new NetworkFirewallPolicy();
        policy.setName(name);
        policy.setId(UUID.randomUUID().toString());
        policy.setRegion(region);
        policy.setArn(arn(region, "firewall-policy/" + name));
        policy.setDescription(textOrNull(request, "Description"));
        policy.setFirewallPolicy(objectMapper.convertValue(definition, MAP));
        policy.setTags(readTags(request.get("Tags")));
        policy.setStatus("ACTIVE");
        policy.setNumberOfAssociations(0);
        touchPolicy(policy);
        savePolicy(policy);
        return policyEnvelope(policy);
    }

    public synchronized ObjectNode describeFirewallPolicy(JsonNode request, String region) {
        NetworkFirewallPolicy policy = requirePolicy(request, region);
        ObjectNode response = policyEnvelope(policy);
        response.set("FirewallPolicy", objectMapper.valueToTree(policy.getFirewallPolicy()));
        return response;
    }

    public synchronized ObjectNode updateFirewallPolicy(JsonNode request, String region) {
        NetworkFirewallPolicy policy = requirePolicy(request, region);
        checkToken(request, policy.getUpdateToken());
        JsonNode definition = request.get("FirewallPolicy");
        if (definition == null || !definition.isObject()) {
            throw invalidRequest("FirewallPolicy is a required parameter.");
        }
        policy.setFirewallPolicy(objectMapper.convertValue(definition, MAP));
        if (request.has("Description")) {
            policy.setDescription(textOrNull(request, "Description"));
        }
        touchPolicy(policy);
        savePolicy(policy);
        return policyEnvelope(policy);
    }

    public synchronized ObjectNode deleteFirewallPolicy(JsonNode request, String region) {
        NetworkFirewallPolicy policy = requirePolicy(request, region);
        if (policy.getNumberOfAssociations() > 0) {
            throw invalidOperation("The firewall policy cannot be deleted because it is still associated with a firewall.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("FirewallPolicyResponse", policyResponse(policy));
        policies.delete(storageKey(policy.getRegion(), policy.getName()));
        return response;
    }

    public synchronized ObjectNode listFirewallPolicies(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("FirewallPolicies");
        for (NetworkFirewallPolicy policy : policies.values()) {
            if (region.equals(policy.getRegion())) {
                ObjectNode meta = list.addObject();
                meta.put("Name", policy.getName());
                meta.put("Arn", policy.getArn());
            }
        }
        return response;
    }

    public synchronized ObjectNode createRuleGroup(JsonNode request, String region) {
        String name = requireText(request, "RuleGroupName");
        String type = requireText(request, "Type");
        if (!request.has("Capacity") || !request.get("Capacity").isNumber()) {
            throw invalidRequest("Capacity is a required parameter.");
        }
        boolean hasRules = textOrNull(request, "Rules") != null;
        boolean hasDefinition = request.hasNonNull("RuleGroup") && request.get("RuleGroup").isObject();
        if (hasRules && hasDefinition) {
            throw invalidRequest("Specify either Rules or RuleGroup, not both.");
        }
        if (!hasRules && !hasDefinition) {
            throw invalidRequest("Rules or RuleGroup is required.");
        }
        if (findRuleGroupByName(region, name, type).isPresent()) {
            throw invalidRequest("A resource with the specified name, " + name + ", already exists.");
        }
        RuleGroupRecord group = new RuleGroupRecord();
        group.setRuleGroupName(name);
        group.setType(type);
        group.setCapacity(request.get("Capacity").asInt());
        group.setRuleGroupId(UUID.randomUUID().toString());
        group.setRegion(region);
        group.setRuleGroupArn(arn(region, ruleGroupResource(type, name)));
        group.setDescription(textOrNull(request, "Description"));
        group.setStatus("ACTIVE");
        group.setTags(readTags(request.get("Tags")));
        applyRuleDefinition(group, request);
        touchRuleGroup(group);
        saveRuleGroup(group);
        return ruleGroupEnvelope(group, false);
    }

    public synchronized ObjectNode describeRuleGroup(JsonNode request, String region) {
        return ruleGroupEnvelope(requireRuleGroup(request, region), true);
    }

    public synchronized ObjectNode describeRuleGroupMetadata(JsonNode request, String region) {
        RuleGroupRecord group = requireRuleGroup(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("RuleGroupArn", group.getRuleGroupArn());
        response.put("RuleGroupName", group.getRuleGroupName());
        if (group.getDescription() != null) {
            response.put("Description", group.getDescription());
        }
        response.put("Type", group.getType());
        response.put("Capacity", group.getCapacity());
        return response;
    }

    public synchronized ObjectNode describeRuleGroupSummary(JsonNode request, String region) {
        RuleGroupRecord group = requireRuleGroup(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("RuleGroupName", group.getRuleGroupName());
        if (group.getDescription() != null) {
            response.put("Description", group.getDescription());
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.set("RuleSummaries", ruleSummaries(group));
        response.set("Summary", summary);
        return response;
    }

    public synchronized ObjectNode updateRuleGroup(JsonNode request, String region) {
        RuleGroupRecord group = requireRuleGroup(request, region);
        checkToken(request, group.getUpdateToken());
        boolean hasRules = textOrNull(request, "Rules") != null;
        boolean hasDefinition = request.hasNonNull("RuleGroup") && request.get("RuleGroup").isObject();
        if (hasRules && hasDefinition) {
            throw invalidRequest("Specify either Rules or RuleGroup, not both.");
        }
        if (hasRules || hasDefinition) {
            applyRuleDefinition(group, request);
        }
        if (request.has("Description")) {
            group.setDescription(textOrNull(request, "Description"));
        }
        if (request.has("SummaryConfiguration")) {
            JsonNode summary = request.get("SummaryConfiguration");
            group.setSummaryConfiguration(summary == null || summary.isNull() ? null : summary.deepCopy());
        }
        touchRuleGroup(group);
        saveRuleGroup(group);
        return ruleGroupEnvelope(group, false);
    }

    public synchronized ObjectNode deleteRuleGroup(JsonNode request, String region) {
        RuleGroupRecord group = requireRuleGroup(request, region);
        if (associationCount(group.getRuleGroupArn()) > 0) {
            throw invalidOperation("The specified resource is currently being used by another resource.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        group.setStatus("DELETING");
        response.set("RuleGroupResponse", ruleGroupResponse(group));
        ruleGroups.delete(ruleGroupKey(group.getRegion(), group.getType(), group.getRuleGroupName()));
        return response;
    }

    public synchronized ObjectNode listRuleGroups(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("RuleGroups");
        String type = textOrNull(request, "Type");
        for (RuleGroupRecord group : ruleGroups.values()) {
            if (!region.equals(group.getRegion())) {
                continue;
            }
            if (type != null && !type.equals(group.getType())) {
                continue;
            }
            ObjectNode meta = list.addObject();
            meta.put("Name", group.getRuleGroupName());
            meta.put("Arn", group.getRuleGroupArn());
        }
        return response;
    }

    public synchronized ObjectNode createFirewall(JsonNode request, String region) {
        String name = requireText(request, "FirewallName");
        String policyArn = requireText(request, "FirewallPolicyArn");
        String vpcId = requireText(request, "VpcId");
        if (findFirewallByName(region, name).isPresent()) {
            throw invalidRequest("A resource with the specified name, " + name + ", already exists.");
        }
        NetworkFirewallPolicy policy = requirePolicyByArn(policyArn);
        List<NetworkFirewallSubnetMapping> mappings = readSubnetMappings(request.get("SubnetMappings"), region);
        if (mappings.isEmpty()) {
            throw invalidRequest("SubnetMappings is a required parameter.");
        }
        NetworkFirewall firewall = new NetworkFirewall();
        firewall.setName(name);
        firewall.setId(UUID.randomUUID().toString());
        firewall.setRegion(region);
        firewall.setArn(arn(region, "firewall/" + name));
        firewall.setFirewallPolicyArn(policy.getArn());
        firewall.setVpcId(vpcId);
        firewall.setDescription(textOrNull(request, "Description"));
        firewall.setDeleteProtection(boolOr(request, "DeleteProtection", false));
        firewall.setSubnetChangeProtection(boolOr(request, "SubnetChangeProtection", false));
        firewall.setFirewallPolicyChangeProtection(boolOr(request, "FirewallPolicyChangeProtection", false));
        firewall.setTags(readTags(request.get("Tags")));
        firewall.setEnabledAnalysisTypes(stringList(request.get("EnabledAnalysisTypes")));
        firewall.setSubnetMappings(mappings);
        touchFirewall(firewall);
        saveFirewall(firewall);
        policy.setNumberOfAssociations(policy.getNumberOfAssociations() + 1);
        savePolicy(policy);
        return firewallEnvelope(firewall);
    }

    public synchronized ObjectNode describeFirewall(JsonNode request, String region) {
        return firewallEnvelope(requireFirewall(request, region));
    }

    public synchronized ObjectNode listFirewalls(JsonNode request, String region) {
        List<String> vpcIds = stringList(request.get("VpcIds"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("Firewalls");
        for (NetworkFirewall firewall : firewalls.values()) {
            if (!region.equals(firewall.getRegion())) {
                continue;
            }
            if (!vpcIds.isEmpty() && !vpcIds.contains(firewall.getVpcId())) {
                continue;
            }
            ObjectNode meta = list.addObject();
            meta.put("FirewallName", firewall.getName());
            meta.put("FirewallArn", firewall.getArn());
        }
        return response;
    }

    public synchronized ObjectNode deleteFirewall(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        if (firewall.isDeleteProtection()) {
            throw invalidOperation("The firewall cannot be deleted because DeleteProtection is enabled.");
        }
        ObjectNode response = firewallEnvelope(firewall);
        firewalls.delete(storageKey(firewall.getRegion(), firewall.getName()));
        findPolicyByArn(firewall.getFirewallPolicyArn()).ifPresent(policy -> {
            policy.setNumberOfAssociations(Math.max(0, policy.getNumberOfAssociations() - 1));
            savePolicy(policy);
        });
        return response;
    }

    public synchronized ObjectNode associateFirewallPolicy(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        checkToken(request, firewall.getUpdateToken());
        if (firewall.isFirewallPolicyChangeProtection()) {
            throw invalidOperation("The firewall policy cannot be changed because FirewallPolicyChangeProtection is enabled.");
        }
        String policyArn = requireText(request, "FirewallPolicyArn");
        NetworkFirewallPolicy next = requirePolicyByArn(policyArn);
        String previousArn = firewall.getFirewallPolicyArn();
        if (!policyArn.equals(previousArn)) {
            findPolicyByArn(previousArn).ifPresent(policy -> {
                policy.setNumberOfAssociations(Math.max(0, policy.getNumberOfAssociations() - 1));
                savePolicy(policy);
            });
            next.setNumberOfAssociations(next.getNumberOfAssociations() + 1);
            savePolicy(next);
            firewall.setFirewallPolicyArn(next.getArn());
        }
        touchFirewall(firewall);
        saveFirewall(firewall);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewall.getArn());
        response.put("FirewallName", firewall.getName());
        response.put("FirewallPolicyArn", firewall.getFirewallPolicyArn());
        response.put("UpdateToken", firewall.getUpdateToken());
        return response;
    }

    public synchronized ObjectNode associateSubnets(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        checkToken(request, firewall.getUpdateToken());
        if (firewall.isSubnetChangeProtection()) {
            throw invalidOperation("The firewall subnets cannot be changed because SubnetChangeProtection is enabled.");
        }
        List<NetworkFirewallSubnetMapping> extra = readSubnetMappings(request.get("SubnetMappings"), region);
        for (NetworkFirewallSubnetMapping mapping : extra) {
            boolean exists = firewall.getSubnetMappings().stream()
                    .anyMatch(existing -> mapping.getSubnetId().equals(existing.getSubnetId()));
            if (!exists) {
                firewall.getSubnetMappings().add(mapping);
            }
        }
        touchFirewall(firewall);
        saveFirewall(firewall);
        return subnetAssociationResponse(firewall);
    }

    public synchronized ObjectNode disassociateSubnets(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        checkToken(request, firewall.getUpdateToken());
        if (firewall.isSubnetChangeProtection()) {
            throw invalidOperation("The firewall subnets cannot be changed because SubnetChangeProtection is enabled.");
        }
        List<String> subnetIds = stringList(request.get("SubnetIds"));
        firewall.getSubnetMappings().removeIf(mapping -> subnetIds.contains(mapping.getSubnetId()));
        touchFirewall(firewall);
        saveFirewall(firewall);
        return subnetAssociationResponse(firewall);
    }

    public synchronized ObjectNode updateFirewallDescription(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        checkToken(request, firewall.getUpdateToken());
        firewall.setDescription(textOrNull(request, "Description"));
        touchFirewall(firewall);
        saveFirewall(firewall);
        return namedFirewallToken(firewall);
    }

    public synchronized ObjectNode updateFirewallDeleteProtection(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        checkToken(request, firewall.getUpdateToken());
        firewall.setDeleteProtection(boolOr(request, "DeleteProtection", false));
        touchFirewall(firewall);
        saveFirewall(firewall);
        ObjectNode response = namedFirewallToken(firewall);
        response.put("DeleteProtection", firewall.isDeleteProtection());
        return response;
    }

    public synchronized ObjectNode updateSubnetChangeProtection(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        checkToken(request, firewall.getUpdateToken());
        firewall.setSubnetChangeProtection(boolOr(request, "SubnetChangeProtection", false));
        touchFirewall(firewall);
        saveFirewall(firewall);
        ObjectNode response = namedFirewallToken(firewall);
        response.put("SubnetChangeProtection", firewall.isSubnetChangeProtection());
        return response;
    }

    public synchronized ObjectNode updateFirewallPolicyChangeProtection(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        checkToken(request, firewall.getUpdateToken());
        firewall.setFirewallPolicyChangeProtection(boolOr(request, "FirewallPolicyChangeProtection", false));
        touchFirewall(firewall);
        saveFirewall(firewall);
        ObjectNode response = namedFirewallToken(firewall);
        response.put("FirewallPolicyChangeProtection", firewall.isFirewallPolicyChangeProtection());
        return response;
    }

    public synchronized ObjectNode describeLoggingConfiguration(JsonNode request, String region) {
        return loggingNode(requireFirewall(request, region));
    }

    public synchronized ObjectNode updateLoggingConfiguration(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        firewall.setLogDestinationConfigs(readLogDestinations(request.get("LoggingConfiguration")));
        saveFirewall(firewall);
        return loggingNode(firewall);
    }

    public synchronized ObjectNode startFlowCapture(JsonNode request, String region) {
        return startFlowOperation(request, region, "FLOW_CAPTURE");
    }

    public synchronized ObjectNode startFlowFlush(JsonNode request, String region) {
        return startFlowOperation(request, region, "FLOW_FLUSH");
    }

    public synchronized ObjectNode describeFlowOperation(JsonNode request, String region) {
        NetworkFirewallFlowOperation operation = requireFlowOperation(request, region);
        ObjectNode response = flowOperationNode(operation);
        ObjectNode details = response.putObject("FlowOperation");
        if (operation.getMinimumFlowAgeInSeconds() != null) {
            details.put("MinimumFlowAgeInSeconds", operation.getMinimumFlowAgeInSeconds());
        }
        if (operation.getFlowFilters() != null) {
            details.set("FlowFilters", operation.getFlowFilters());
        } else {
            details.putArray("FlowFilters");
        }
        return response;
    }

    public synchronized ObjectNode listFlowOperations(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        String type = textOrNull(request, "FlowOperationType");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("FlowOperations");
        for (NetworkFirewallFlowOperation operation : flowOperations.values()) {
            if (!firewall.getArn().equals(operation.getFirewallArn())) {
                continue;
            }
            if (type != null && !type.equals(operation.getFlowOperationType())) {
                continue;
            }
            ObjectNode meta = list.addObject();
            meta.put("FlowOperationId", operation.getFlowOperationId());
            meta.put("FlowOperationType", operation.getFlowOperationType());
            meta.put("FlowRequestTimestamp", operation.getFlowRequestTimestamp());
            meta.put("FlowOperationStatus", operation.getFlowOperationStatus());
        }
        return response;
    }

    public synchronized ObjectNode listFlowOperationResults(JsonNode request, String region) {
        NetworkFirewallFlowOperation operation = requireFlowOperation(request, region);
        ObjectNode response = flowOperationNode(operation);
        response.putArray("Flows");
        return response;
    }

    public synchronized ObjectNode startAnalysisReport(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        String analysisType = requireText(request, "AnalysisType");
        if (!firewall.getEnabledAnalysisTypes().contains(analysisType)) {
            throw invalidRequest("Analysis type " + analysisType
                    + " is not enabled on the specified firewall.");
        }
        NetworkFirewallAnalysisReport report = new NetworkFirewallAnalysisReport();
        report.setAnalysisReportId(UUID.randomUUID().toString());
        report.setFirewallArn(firewall.getArn());
        report.setAnalysisType(analysisType);
        report.setStatus("COMPLETED");
        report.setReportTime(now());
        analysisReports.put(report.getAnalysisReportId(), report);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AnalysisReportId", report.getAnalysisReportId());
        return response;
    }

    public synchronized ObjectNode listAnalysisReports(JsonNode request, String region) {
        NetworkFirewall firewall = requireFirewall(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("AnalysisReports");
        for (NetworkFirewallAnalysisReport report : analysisReports.values()) {
            if (firewall.getArn().equals(report.getFirewallArn())) {
                ObjectNode node = list.addObject();
                node.put("AnalysisReportId", report.getAnalysisReportId());
                node.put("AnalysisType", report.getAnalysisType());
                node.put("ReportTime", report.getReportTime());
                node.put("Status", report.getStatus());
            }
        }
        return response;
    }

    public synchronized ObjectNode getAnalysisReportResults(JsonNode request, String region) {
        requireFirewall(request, region);
        String reportId = requireText(request, "AnalysisReportId");
        NetworkFirewallAnalysisReport report = analysisReports.get(reportId)
                .orElseThrow(() -> notFound("Unable to locate analysis report " + reportId + "."));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Status", report.getStatus());
        response.put("ReportTime", report.getReportTime());
        response.put("StartTime", report.getReportTime());
        response.put("EndTime", report.getReportTime());
        response.put("AnalysisType", report.getAnalysisType());
        response.putArray("AnalysisReportResults");
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode request, String region) {
        String arn = requireText(request, "ResourceArn");
        Map<String, String> tags = readTags(request.get("Tags"));
        Tagged tagged = requireTagged(arn);
        tagged.tags().putAll(tags);
        tagged.save();
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        String arn = requireText(request, "ResourceArn");
        Tagged tagged = requireTagged(arn);
        for (String key : stringList(request.get("TagKeys"))) {
            tagged.tags().remove(key);
        }
        tagged.save();
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode listTagsForResource(JsonNode request, String region) {
        String arn = requireText(request, "ResourceArn");
        Tagged tagged = requireTagged(arn);
        ObjectNode response = objectMapper.createObjectNode();
        writeTags(response.putArray("Tags"), tagged.tags());
        return response;
    }

    private ObjectNode loggingNode(NetworkFirewall firewall) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewall.getArn());
        ObjectNode logging = response.putObject("LoggingConfiguration");
        ArrayNode configs = logging.putArray("LogDestinationConfigs");
        for (Map<String, Object> dest : firewall.getLogDestinationConfigs()) {
            configs.add(objectMapper.valueToTree(dest));
        }
        return response;
    }

    private List<Map<String, Object>> readLogDestinations(JsonNode logging) {
        if (logging == null || logging.isNull()) {
            return new ArrayList<>();
        }
        JsonNode list = logging.get("LogDestinationConfigs");
        if (list == null || !list.isArray()) {
            return new ArrayList<>();
        }
        return objectMapper.convertValue(list, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private ObjectNode startFlowOperation(JsonNode request, String region, String type) {
        NetworkFirewall firewall = requireFirewall(request, region);
        JsonNode filters = request.get("FlowFilters");
        if (filters == null || !filters.isArray() || filters.isEmpty()) {
            throw invalidRequest("FlowFilters is a required parameter.");
        }
        NetworkFirewallFlowOperation operation = new NetworkFirewallFlowOperation();
        operation.setFlowOperationId(UUID.randomUUID().toString());
        operation.setFirewallArn(firewall.getArn());
        operation.setFlowOperationType(type);
        operation.setFlowOperationStatus("COMPLETED");
        operation.setFlowRequestTimestamp(now());
        operation.setAvailabilityZone(textOrNull(request, "AvailabilityZone"));
        if (request.hasNonNull("MinimumFlowAgeInSeconds")) {
            operation.setMinimumFlowAgeInSeconds(request.get("MinimumFlowAgeInSeconds").asInt());
        }
        operation.setFlowFilters(filters.deepCopy());
        flowOperations.put(operation.getFlowOperationId(), operation);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewall.getArn());
        response.put("FlowOperationId", operation.getFlowOperationId());
        response.put("FlowOperationStatus", operation.getFlowOperationStatus());
        return response;
    }

    private ObjectNode flowOperationNode(NetworkFirewallFlowOperation operation) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", operation.getFirewallArn());
        response.put("FlowOperationId", operation.getFlowOperationId());
        response.put("FlowOperationType", operation.getFlowOperationType());
        response.put("FlowOperationStatus", operation.getFlowOperationStatus());
        response.put("FlowRequestTimestamp", operation.getFlowRequestTimestamp());
        if (operation.getAvailabilityZone() != null) {
            response.put("AvailabilityZone", operation.getAvailabilityZone());
        }
        return response;
    }

    private NetworkFirewallFlowOperation requireFlowOperation(JsonNode request, String region) {
        requireFirewall(request, region);
        String id = requireText(request, "FlowOperationId");
        return flowOperations.get(id)
                .orElseThrow(() -> notFound("Unable to locate flow operation " + id + "."));
    }

    private ObjectNode policyEnvelope(NetworkFirewallPolicy policy) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("UpdateToken", policy.getUpdateToken());
        response.set("FirewallPolicyResponse", policyResponse(policy));
        return response;
    }

    private ObjectNode policyResponse(NetworkFirewallPolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("FirewallPolicyName", policy.getName());
        node.put("FirewallPolicyArn", policy.getArn());
        node.put("FirewallPolicyId", policy.getId());
        if (policy.getDescription() != null) {
            node.put("Description", policy.getDescription());
        }
        node.put("FirewallPolicyStatus", policy.getStatus());
        node.put("NumberOfAssociations", policy.getNumberOfAssociations());
        node.put("LastModifiedTime", policy.getLastModifiedTime());
        writeTags(node.putArray("Tags"), policy.getTags());
        return node;
    }

    private ObjectNode firewallEnvelope(NetworkFirewall firewall) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("UpdateToken", firewall.getUpdateToken());
        response.set("Firewall", firewallNode(firewall));
        response.set("FirewallStatus", firewallStatus(firewall));
        return response;
    }

    private ObjectNode firewallNode(NetworkFirewall firewall) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("FirewallName", firewall.getName());
        node.put("FirewallArn", firewall.getArn());
        node.put("FirewallId", firewall.getId());
        node.put("FirewallPolicyArn", firewall.getFirewallPolicyArn());
        node.put("VpcId", firewall.getVpcId());
        if (firewall.getDescription() != null) {
            node.put("Description", firewall.getDescription());
        }
        node.put("DeleteProtection", firewall.isDeleteProtection());
        node.put("SubnetChangeProtection", firewall.isSubnetChangeProtection());
        node.put("FirewallPolicyChangeProtection", firewall.isFirewallPolicyChangeProtection());
        ArrayNode mappings = node.putArray("SubnetMappings");
        for (NetworkFirewallSubnetMapping mapping : firewall.getSubnetMappings()) {
            ObjectNode item = mappings.addObject();
            item.put("SubnetId", mapping.getSubnetId());
            if (mapping.getIpAddressType() != null) {
                item.put("IPAddressType", mapping.getIpAddressType());
            }
        }
        ArrayNode analysis = node.putArray("EnabledAnalysisTypes");
        for (String type : firewall.getEnabledAnalysisTypes()) {
            analysis.add(type);
        }
        writeTags(node.putArray("Tags"), firewall.getTags());
        return node;
    }

    private ObjectNode firewallStatus(NetworkFirewall firewall) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("Status", "READY");
        status.put("ConfigurationSyncStateSummary", "IN_SYNC");
        ObjectNode syncStates = status.putObject("SyncStates");
        for (NetworkFirewallSubnetMapping mapping : firewall.getSubnetMappings()) {
            String az = mapping.getAvailabilityZone() != null
                    ? mapping.getAvailabilityZone()
                    : firewall.getRegion() + "a";
            ObjectNode state = syncStates.putObject(az);
            ObjectNode attachment = state.putObject("Attachment");
            attachment.put("SubnetId", mapping.getSubnetId());
            attachment.put("EndpointId", mapping.getEndpointId());
            attachment.put("Status", "READY");
        }
        return status;
    }

    private ObjectNode subnetAssociationResponse(NetworkFirewall firewall) {
        ObjectNode response = namedFirewallToken(firewall);
        ArrayNode mappings = response.putArray("SubnetMappings");
        for (NetworkFirewallSubnetMapping mapping : firewall.getSubnetMappings()) {
            ObjectNode item = mappings.addObject();
            item.put("SubnetId", mapping.getSubnetId());
            if (mapping.getIpAddressType() != null) {
                item.put("IPAddressType", mapping.getIpAddressType());
            }
        }
        return response;
    }

    private ObjectNode namedFirewallToken(NetworkFirewall firewall) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FirewallArn", firewall.getArn());
        response.put("FirewallName", firewall.getName());
        response.put("UpdateToken", firewall.getUpdateToken());
        return response;
    }

    private List<NetworkFirewallSubnetMapping> readSubnetMappings(JsonNode node, String region) {
        List<NetworkFirewallSubnetMapping> mappings = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return mappings;
        }
        for (JsonNode item : node) {
            String subnetId = textOrNull(item, "SubnetId");
            if (subnetId == null) {
                throw invalidRequest("SubnetId is a required parameter.");
            }
            NetworkFirewallSubnetMapping mapping = new NetworkFirewallSubnetMapping();
            mapping.setSubnetId(subnetId);
            mapping.setIpAddressType(textOrNull(item, "IPAddressType"));
            mapping.setEndpointId(hexId("vpce-"));
            mapping.setAvailabilityZone(ec2Service.findSubnetById(region, subnetId)
                    .map(Subnet::getAvailabilityZone)
                    .filter(az -> az != null && !az.isBlank())
                    .orElse(region + "a"));
            mappings.add(mapping);
        }
        return mappings;
    }

    private NetworkFirewallPolicy requirePolicy(JsonNode request, String region) {
        String arn = textOrNull(request, "FirewallPolicyArn");
        if (arn != null) {
            return requirePolicyByArn(arn);
        }
        String name = textOrNull(request, "FirewallPolicyName");
        if (name == null) {
            throw invalidRequest("FirewallPolicyArn or FirewallPolicyName is required.");
        }
        return findPolicyByName(region, name)
                .orElseThrow(() -> notFound("Unable to locate firewall policy " + name + "."));
    }

    private NetworkFirewall requireFirewall(JsonNode request, String region) {
        String arn = textOrNull(request, "FirewallArn");
        if (arn != null) {
            return findFirewallByArn(arn)
                    .orElseThrow(() -> notFound("Unable to locate firewall with ARN " + arn + "."));
        }
        String name = textOrNull(request, "FirewallName");
        if (name == null) {
            throw invalidRequest("FirewallArn or FirewallName is required.");
        }
        return findFirewallByName(region, name)
                .orElseThrow(() -> notFound("Unable to locate firewall " + name + "."));
    }

    private NetworkFirewallPolicy requirePolicyByArn(String arn) {
        return findPolicyByArn(arn)
                .orElseThrow(() -> notFound("Unable to locate firewall policy with ARN " + arn + "."));
    }

    private Optional<NetworkFirewallPolicy> findPolicyByName(String region, String name) {
        return policies.get(storageKey(region, name));
    }

    private Optional<NetworkFirewallPolicy> findPolicyByArn(String arn) {
        return policies.values().stream().filter(policy -> arn.equals(policy.getArn())).findFirst();
    }

    private Optional<NetworkFirewall> findFirewallByName(String region, String name) {
        return firewalls.get(storageKey(region, name));
    }

    private Optional<NetworkFirewall> findFirewallByArn(String arn) {
        return firewalls.values().stream().filter(firewall -> arn.equals(firewall.getArn())).findFirst();
    }

    private Tagged requireTagged(String arn) {
        Optional<NetworkFirewall> firewall = findFirewallByArn(arn);
        if (firewall.isPresent()) {
            return new Tagged(firewall.get().getTags(), () -> saveFirewall(firewall.get()));
        }
        Optional<NetworkFirewallPolicy> policy = findPolicyByArn(arn);
        if (policy.isPresent()) {
            return new Tagged(policy.get().getTags(), () -> savePolicy(policy.get()));
        }
        Optional<RuleGroupRecord> group = findRuleGroupByArn(arn);
        if (group.isPresent()) {
            return new Tagged(group.get().getTags(), () -> saveRuleGroup(group.get()));
        }
        throw notFound("Unable to locate resource " + arn + ".");
    }

    private void savePolicy(NetworkFirewallPolicy policy) {
        policies.put(storageKey(policy.getRegion(), policy.getName()), policy);
    }

    private void saveFirewall(NetworkFirewall firewall) {
        firewalls.put(storageKey(firewall.getRegion(), firewall.getName()), firewall);
    }

    private void saveRuleGroup(RuleGroupRecord group) {
        ruleGroups.put(ruleGroupKey(group.getRegion(), group.getType(), group.getRuleGroupName()), group);
    }

    private void touchRuleGroup(RuleGroupRecord group) {
        group.setUpdateToken(UUID.randomUUID().toString());
        group.setLastModifiedTime(now());
    }

    private RuleGroupRecord requireRuleGroup(JsonNode request, String region) {
        String arn = textOrNull(request, "RuleGroupArn");
        if (arn != null) {
            return findRuleGroupByArn(arn)
                    .orElseThrow(() -> notFound("Unable to locate a rule group with the specified identifier."));
        }
        String name = textOrNull(request, "RuleGroupName");
        String type = textOrNull(request, "Type");
        if (name == null || type == null) {
            throw invalidRequest("You must specify a rule group ARN, or a name and type.");
        }
        return findRuleGroupByName(region, name, type)
                .orElseThrow(() -> notFound("Unable to locate a rule group with the specified identifier."));
    }

    private Optional<RuleGroupRecord> findRuleGroupByName(String region, String name, String type) {
        return ruleGroups.get(ruleGroupKey(region, type, name));
    }

    private Optional<RuleGroupRecord> findRuleGroupByArn(String arn) {
        return ruleGroups.values().stream()
                .filter(group -> arn.equals(group.getRuleGroupArn()))
                .findFirst();
    }

    private void applyRuleDefinition(RuleGroupRecord group, JsonNode request) {
        if (textOrNull(request, "Rules") != null) {
            String rules = request.get("Rules").asText();
            ObjectNode definition = objectMapper.createObjectNode();
            definition.putObject("RulesSource").put("RulesString", rules);
            group.setDefinition(definition);
        } else if (request.hasNonNull("RuleGroup") && request.get("RuleGroup").isObject()) {
            group.setDefinition(request.get("RuleGroup").deepCopy());
        }
        if (request.has("SummaryConfiguration")) {
            JsonNode summary = request.get("SummaryConfiguration");
            group.setSummaryConfiguration(summary == null || summary.isNull() ? null : summary.deepCopy());
        }
    }

    private ObjectNode ruleGroupEnvelope(RuleGroupRecord group, boolean includeDefinition) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("UpdateToken", group.getUpdateToken());
        if (includeDefinition && group.getDefinition() != null && !group.getDefinition().isNull()) {
            response.set("RuleGroup", group.getDefinition());
        }
        response.set("RuleGroupResponse", ruleGroupResponse(group));
        return response;
    }

    private ObjectNode ruleGroupResponse(RuleGroupRecord group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RuleGroupArn", group.getRuleGroupArn());
        node.put("RuleGroupName", group.getRuleGroupName());
        node.put("RuleGroupId", group.getRuleGroupId());
        if (group.getDescription() != null) {
            node.put("Description", group.getDescription());
        }
        node.put("Type", group.getType());
        node.put("Capacity", group.getCapacity());
        node.put("RuleGroupStatus", group.getStatus());
        node.put("NumberOfAssociations", associationCount(group.getRuleGroupArn()));
        if (group.getSummaryConfiguration() != null && !group.getSummaryConfiguration().isNull()) {
            node.set("SummaryConfiguration", group.getSummaryConfiguration());
        }
        writeTags(node.putArray("Tags"), group.getTags());
        return node;
    }

    private ArrayNode ruleSummaries(RuleGroupRecord group) {
        ArrayNode summaries = objectMapper.createArrayNode();
        JsonNode config = group.getSummaryConfiguration();
        if (config == null || config.isNull() || !config.has("RuleOptions") || !config.get("RuleOptions").isArray()) {
            return summaries;
        }
        List<String> options = new ArrayList<>();
        for (JsonNode option : config.get("RuleOptions")) {
            if (option != null && option.isTextual()) {
                options.add(option.asText());
            }
        }
        if (options.isEmpty()) {
            return summaries;
        }
        String rules = rulesString(group);
        if (rules == null || rules.isBlank()) {
            return summaries;
        }
        for (String line : rules.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            ObjectNode summary = summaries.addObject();
            if (options.contains("SID")) {
                Matcher sid = SID.matcher(trimmed);
                if (sid.find()) {
                    summary.put("SID", sid.group(1));
                }
            }
            if (options.contains("MSG")) {
                Matcher msg = MSG.matcher(trimmed);
                if (msg.find()) {
                    summary.put("Msg", msg.group(1));
                }
            }
            if (options.contains("METADATA")) {
                Matcher metadata = METADATA.matcher(trimmed);
                if (metadata.find()) {
                    summary.put("Metadata", metadata.group(1).trim());
                }
            }
        }
        return summaries;
    }

    private static String rulesString(RuleGroupRecord group) {
        if (group.getDefinition() == null || group.getDefinition().isNull()) {
            return null;
        }
        JsonNode rules = group.getDefinition().path("RulesSource").path("RulesString");
        return rules.isTextual() ? rules.asText() : null;
    }

    private int associationCount(String ruleGroupArn) {
        int count = 0;
        for (NetworkFirewallPolicy policy : policies.values()) {
            JsonNode definition = objectMapper.valueToTree(policy.getFirewallPolicy());
            if (references(definition.path("StatefulRuleGroupReferences"), ruleGroupArn)
                    || references(definition.path("StatelessRuleGroupReferences"), ruleGroupArn)) {
                count++;
            }
        }
        return count;
    }

    private static boolean references(JsonNode refs, String arn) {
        if (refs == null || !refs.isArray()) {
            return false;
        }
        for (JsonNode ref : refs) {
            JsonNode resourceArn = ref.path("ResourceArn");
            if (resourceArn.isTextual() && arn.equals(resourceArn.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String ruleGroupResource(String type, String name) {
        String kind = "STATELESS".equals(type) ? "stateless-rulegroup" : "stateful-rulegroup";
        return kind + "/" + name;
    }

    private static String ruleGroupKey(String region, String type, String name) {
        return region + ":" + type + ":" + name;
    }

    private void touchPolicy(NetworkFirewallPolicy policy) {
        policy.setUpdateToken(UUID.randomUUID().toString());
        policy.setLastModifiedTime(now());
    }

    private void touchFirewall(NetworkFirewall firewall) {
        firewall.setUpdateToken(UUID.randomUUID().toString());
    }

    private void checkToken(JsonNode request, String current) {
        String token = textOrNull(request, "UpdateToken");
        if (token != null && current != null && !token.equals(current)) {
            throw new AwsException("InvalidTokenException",
                    "The supplied UpdateToken is invalid.", 400);
        }
    }

    private String arn(String region, String resource) {
        return regionResolver.buildArn(SERVICE, region, resource);
    }

    private static void writeTags(ArrayNode array, Map<String, String> tags) {
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tag = array.addObject();
            tag.put("Key", entry.getKey());
            tag.put("Value", entry.getValue());
        }
    }

    private static Map<String, String> readTags(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags == null || !tags.isArray()) {
            return result;
        }
        for (JsonNode tag : tags) {
            if (tag.hasNonNull("Key") && tag.hasNonNull("Value")) {
                result.put(tag.get("Key").asText(), tag.get("Value").asText());
            }
        }
        return result;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (!item.isNull()) {
                String text = item.asText();
                if (text != null && !text.isBlank()) {
                    values.add(text);
                }
            }
        }
        return values;
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalidRequest(field + " is a required parameter.");
        }
        return value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null || !request.hasNonNull(field)) {
            return null;
        }
        String value = request.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean boolOr(JsonNode request, String field, boolean fallback) {
        if (request == null || !request.hasNonNull(field)) {
            return fallback;
        }
        return request.get(field).asBoolean();
    }

    private static String storageKey(String region, String name) {
        return region + ":" + name;
    }

    private static String hexId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException invalidOperation(String message) {
        return new AwsException("InvalidOperationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private record Tagged(Map<String, String> tags, Runnable persist) {
        void save() {
            persist.run();
        }
    }
}
