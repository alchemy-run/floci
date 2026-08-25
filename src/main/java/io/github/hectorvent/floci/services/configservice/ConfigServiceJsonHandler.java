package io.github.hectorvent.floci.services.configservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.configservice.model.AggregationAuthorization;
import io.github.hectorvent.floci.services.configservice.model.ConfigRule;
import io.github.hectorvent.floci.services.configservice.model.ConfigRuleEvaluationStatus;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorder;
import io.github.hectorvent.floci.services.configservice.model.ConfigurationRecorderStatus;
import io.github.hectorvent.floci.services.configservice.model.ConformancePack;
import io.github.hectorvent.floci.services.configservice.model.ConformancePackStatusDetail;
import io.github.hectorvent.floci.services.configservice.model.CustomResourceConfig;
import io.github.hectorvent.floci.services.configservice.model.DeliveryChannel;
import io.github.hectorvent.floci.services.configservice.model.RetentionConfiguration;
import io.github.hectorvent.floci.services.configservice.model.StoredResourceEvaluation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ConfigServiceJsonHandler {

    private final AwsConfigService service;
    private final ObjectMapper mapper;

    @Inject
    public ConfigServiceJsonHandler(AwsConfigService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "PutConfigRule" -> putConfigRule(request, region);
            case "DeleteConfigRule" -> deleteConfigRule(request, region);
            case "DescribeConfigRules" -> describeConfigRules(request, region);
            case "DescribeComplianceByConfigRule" -> describeComplianceByConfigRule(request, region);
            case "DescribeConfigRuleEvaluationStatus" -> describeConfigRuleEvaluationStatus(request, region);
            case "StartConfigRulesEvaluation" -> startConfigRulesEvaluation(request, region);
            case "PutConformancePack" -> putConformancePack(request, region);
            case "DeleteConformancePack" -> deleteConformancePack(request, region);
            case "DescribeConformancePacks" -> describeConformancePacks(request, region);
            case "DescribeConformancePackStatus" -> describeConformancePackStatus(request, region);
            case "PutConfigurationRecorder" -> putConfigurationRecorder(request, region);
            case "DeleteConfigurationRecorder" -> deleteConfigurationRecorder(request, region);
            case "DescribeConfigurationRecorders" -> describeConfigurationRecorders(request, region);
            case "StartConfigurationRecorder" -> startConfigurationRecorder(request, region);
            case "StopConfigurationRecorder" -> stopConfigurationRecorder(request, region);
            case "DescribeConfigurationRecorderStatus" -> describeConfigurationRecorderStatus(request, region);
            case "PutDeliveryChannel" -> putDeliveryChannel(request, region);
            case "DescribeDeliveryChannels" -> describeDeliveryChannels(request, region);
            case "PutRetentionConfiguration" -> putRetentionConfiguration(request, region);
            case "DescribeRetentionConfigurations" -> describeRetentionConfigurations(request, region);
            case "DeleteRetentionConfiguration" -> deleteRetentionConfiguration(request, region);
            case "PutAggregationAuthorization" -> putAggregationAuthorization(request, region);
            case "DescribeAggregationAuthorizations" -> describeAggregationAuthorizations(region);
            case "DeleteAggregationAuthorization" -> deleteAggregationAuthorization(request, region);
            case "TagResource" -> tagResource(request);
            case "UntagResource" -> untagResource(request);
            case "ListTagsForResource" -> listTagsForResource(request);
            case "SelectResourceConfig" -> selectResourceConfig();
            case "ListDiscoveredResources" -> listDiscoveredResources(request, region);
            case "GetDiscoveredResourceCounts" -> getDiscoveredResourceCounts(region);
            case "BatchGetResourceConfig" -> batchGetResourceConfig(request, region);
            case "GetResourceConfigHistory" -> getResourceConfigHistory(request, region);
            case "DescribeComplianceByResource" -> describeComplianceByResource();
            case "GetComplianceDetailsByResource" -> getComplianceDetailsByResource();
            case "GetComplianceSummaryByConfigRule" -> getComplianceSummaryByConfigRule();
            case "GetComplianceSummaryByResourceType" -> getComplianceSummaryByResourceType();
            case "GetComplianceDetailsByConfigRule" -> getComplianceDetailsByConfigRule(request, region);
            case "PutEvaluations" -> putEvaluations();
            case "PutExternalEvaluation" -> putExternalEvaluation(request, region);
            case "PutResourceConfig" -> putResourceConfig(request, region);
            case "DeleteResourceConfig" -> deleteResourceConfig(request, region);
            case "StartResourceEvaluation" -> startResourceEvaluation(request, region);
            case "GetResourceEvaluationSummary" -> getResourceEvaluationSummary(request, region);
            case "ListResourceEvaluations" -> listResourceEvaluations(request, region);
            default -> throw new io.github.hectorvent.floci.core.common.AwsException(
                    "InvalidAction", "Could not find operation " + action, 400);
        };
    }

    // --- Config Rules ---

    private Response putConfigRule(JsonNode req, String region) throws Exception {
        ConfigRule incoming = mapper.treeToValue(req.path("ConfigRule"), ConfigRule.class);
        if (incoming == null) {
            throw new io.github.hectorvent.floci.core.common.AwsException(
                    "InvalidParameterValueException", "ConfigRule is required.", 400);
        }
        service.putConfigRule(region, incoming, extractTags(req));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteConfigRule(JsonNode req, String region) {
        String ruleName = req.path("ConfigRuleName").asText(null);
        service.deleteConfigRule(region, ruleName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConfigRules(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        List<ConfigRule> rules = service.describeConfigRules(region, ruleNames);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConfigRules", mapper.valueToTree(rules));
        return Response.ok(resp).build();
    }

    private Response describeComplianceByConfigRule(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        List<ConfigRule> rules = service.describeConfigRules(region, ruleNames);
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode arr = resp.putArray("ComplianceByConfigRules");
        for (ConfigRule rule : rules) {
            ObjectNode entry = mapper.createObjectNode();
            entry.put("ConfigRuleName", rule.configRuleName());
            ObjectNode compliance = mapper.createObjectNode();
            compliance.put("ComplianceType", "INSUFFICIENT_DATA");
            entry.set("Compliance", compliance);
            arr.add(entry);
        }
        return Response.ok(resp).build();
    }

    private Response describeConfigRuleEvaluationStatus(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        List<ConfigRuleEvaluationStatus> statuses = service.describeConfigRuleEvaluationStatus(region, ruleNames);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConfigRulesEvaluationStatus", mapper.valueToTree(statuses));
        return Response.ok(resp).build();
    }

    private Response startConfigRulesEvaluation(JsonNode req, String region) {
        List<String> ruleNames = extractStringList(req, "ConfigRuleNames");
        service.startConfigRulesEvaluation(region, ruleNames);
        return Response.ok(mapper.createObjectNode()).build();
    }

    // --- Configuration Recorder ---

    private Response putConfigurationRecorder(JsonNode req, String region) throws Exception {
        ConfigurationRecorder recorder = mapper.treeToValue(req.path("ConfigurationRecorder"), ConfigurationRecorder.class);
        service.putConfigurationRecorder(region, recorder);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteConfigurationRecorder(JsonNode req, String region) {
        String name = req.path("ConfigurationRecorderName").asText(null);
        service.deleteConfigurationRecorder(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConfigurationRecorders(JsonNode req, String region) {
        List<String> names = extractStringList(req, "ConfigurationRecorderNames");
        List<ConfigurationRecorder> recorders = service.describeConfigurationRecorders(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConfigurationRecorders", mapper.valueToTree(recorders));
        return Response.ok(resp).build();
    }

    private Response startConfigurationRecorder(JsonNode req, String region) {
        String name = req.path("ConfigurationRecorderName").asText(null);
        service.startConfigurationRecorder(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response stopConfigurationRecorder(JsonNode req, String region) {
        String name = req.path("ConfigurationRecorderName").asText(null);
        service.stopConfigurationRecorder(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConfigurationRecorderStatus(JsonNode req, String region) {
        List<String> names = extractStringList(req, "ConfigurationRecorderNames");
        List<ConfigurationRecorderStatus> statuses = service.describeConfigurationRecorderStatus(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConfigurationRecordersStatus", mapper.valueToTree(statuses));
        return Response.ok(resp).build();
    }

    // --- Delivery Channel ---

    private Response putDeliveryChannel(JsonNode req, String region) throws Exception {
        DeliveryChannel channel = mapper.treeToValue(req.path("DeliveryChannel"), DeliveryChannel.class);
        service.putDeliveryChannel(region, channel);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeDeliveryChannels(JsonNode req, String region) {
        List<String> names = extractStringList(req, "DeliveryChannelNames");
        List<DeliveryChannel> channels = service.describeDeliveryChannels(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("DeliveryChannels", mapper.valueToTree(channels));
        return Response.ok(resp).build();
    }

    // --- Retention Configuration ---

    private Response putRetentionConfiguration(JsonNode req, String region) {
        JsonNode daysNode = req.has("RetentionPeriodInDays")
                ? req.path("RetentionPeriodInDays")
                : req.path("retentionPeriodInDays");
        if (daysNode.isMissingNode() || daysNode.isNull() || !daysNode.isNumber()) {
            throw new io.github.hectorvent.floci.core.common.AwsException(
                    "InvalidParameterValueException", "RetentionPeriodInDays is required.", 400);
        }
        RetentionConfiguration retention = service.putRetentionConfiguration(region, daysNode.asInt());
        ObjectNode resp = mapper.createObjectNode();
        resp.set("RetentionConfiguration", mapper.valueToTree(retention));
        return Response.ok(resp).build();
    }

    private Response describeRetentionConfigurations(JsonNode req, String region) {
        List<String> names = extractStringList(req, "RetentionConfigurationNames");
        if (names.isEmpty()) {
            names = extractStringList(req, "retentionConfigurationNames");
        }
        List<RetentionConfiguration> retentions = service.describeRetentionConfigurations(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("RetentionConfigurations", mapper.valueToTree(retentions));
        return Response.ok(resp).build();
    }

    private Response deleteRetentionConfiguration(JsonNode req, String region) {
        String name = req.path("RetentionConfigurationName").asText(null);
        if (name == null || name.isBlank()) {
            name = req.path("retentionConfigurationName").asText(null);
        }
        service.deleteRetentionConfiguration(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    // --- Conformance Packs ---

    private Response putConformancePack(JsonNode req, String region) {
        String packName = req.path("ConformancePackName").asText(null);
        String templateS3Uri = req.has("TemplateS3Uri") ? req.path("TemplateS3Uri").asText(null) : null;
        String templateBody = req.has("TemplateBody") ? req.path("TemplateBody").asText(null) : null;
        ConformancePack pack = service.putConformancePack(region, packName, templateS3Uri, templateBody);
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ConformancePackArn", pack.conformancePackArn());
        return Response.ok(resp).build();
    }

    private Response deleteConformancePack(JsonNode req, String region) {
        String packName = req.path("ConformancePackName").asText(null);
        service.deleteConformancePack(region, packName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response describeConformancePacks(JsonNode req, String region) {
        List<String> names = extractStringList(req, "ConformancePackNames");
        List<ConformancePack> packs = service.describeConformancePacks(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConformancePackDetails", mapper.valueToTree(packs));
        return Response.ok(resp).build();
    }

    private Response describeConformancePackStatus(JsonNode req, String region) {
        List<String> names = extractStringList(req, "ConformancePackNames");
        List<ConformancePackStatusDetail> statuses = service.describeConformancePackStatus(region, names);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ConformancePackStatusDetails", mapper.valueToTree(statuses));
        return Response.ok(resp).build();
    }

    // --- Aggregation Authorizations ---

    private Response putAggregationAuthorization(JsonNode req, String region) {
        String accountId = req.path("AuthorizedAccountId").asText(null);
        String authorizedRegion = req.path("AuthorizedAwsRegion").asText(null);
        List<Map<String, String>> tagList = new ArrayList<>();
        if (req.has("Tags")) {
            req.path("Tags").forEach(t -> tagList.add(Map.of(
                    "Key", t.path("Key").asText(),
                    "Value", t.path("Value").asText())));
        }
        AggregationAuthorization auth = service.putAggregationAuthorization(
                region, accountId, authorizedRegion, tagList);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("AggregationAuthorization", mapper.valueToTree(auth));
        return Response.ok(resp).build();
    }

    private Response describeAggregationAuthorizations(String region) {
        List<AggregationAuthorization> auths = service.describeAggregationAuthorizations(region);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("AggregationAuthorizations", mapper.valueToTree(auths));
        return Response.ok(resp).build();
    }

    private Response deleteAggregationAuthorization(JsonNode req, String region) {
        String accountId = req.path("AuthorizedAccountId").asText(null);
        String authorizedRegion = req.path("AuthorizedAwsRegion").asText(null);
        service.deleteAggregationAuthorization(region, accountId, authorizedRegion);
        return Response.ok(mapper.createObjectNode()).build();
    }

    // --- Tagging ---

    private Response tagResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<Map<String, String>> tagList = new ArrayList<>();
        req.path("Tags").forEach(t -> tagList.add(Map.of(
                "Key", t.path("Key").asText(),
                "Value", t.path("Value").asText())));
        service.tagResource(arn, tagList);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<String> tagKeys = new ArrayList<>();
        req.path("TagKeys").forEach(k -> tagKeys.add(k.asText()));
        service.untagResource(arn, tagKeys);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response listTagsForResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<Map<String, String>> tagList = service.listTagsForResource(arn);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("Tags", mapper.valueToTree(tagList));
        return Response.ok(resp).build();
    }

    // --- Discovered resources / querying ---

    private Response selectResourceConfig() {
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("Results");
        return Response.ok(resp).build();
    }

    private Response listDiscoveredResources(JsonNode req, String region) {
        String resourceType = text(req, "resourceType", "ResourceType");
        List<CustomResourceConfig> resources = service.listDiscoveredResources(region, resourceType);
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode identifiers = resp.putArray("resourceIdentifiers");
        for (CustomResourceConfig resource : resources) {
            ObjectNode id = identifiers.addObject();
            id.put("resourceType", resource.resourceType());
            id.put("resourceId", resource.resourceId());
            if (resource.resourceName() != null) {
                id.put("resourceName", resource.resourceName());
            }
        }
        return Response.ok(resp).build();
    }

    private Response getDiscoveredResourceCounts(String region) {
        List<CustomResourceConfig> resources = service.allCustomResources(region);
        ObjectNode resp = mapper.createObjectNode();
        resp.put("totalDiscoveredResources", resources.size());
        ArrayNode counts = resp.putArray("resourceCounts");
        Map<String, Integer> byType = new java.util.LinkedHashMap<>();
        for (CustomResourceConfig resource : resources) {
            byType.merge(resource.resourceType(), 1, Integer::sum);
        }
        byType.forEach((type, count) -> {
            ObjectNode entry = counts.addObject();
            entry.put("resourceType", type);
            entry.put("count", count);
        });
        return Response.ok(resp).build();
    }

    private Response batchGetResourceConfig(JsonNode req, String region) {
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode items = resp.putArray("baseConfigurationItems");
        ArrayNode unprocessed = resp.putArray("unprocessedResourceKeys");
        JsonNode keys = field(req, "resourceKeys", "ResourceKeys");
        if (keys.isArray()) {
            for (JsonNode key : keys) {
                String resourceType = text(key, "resourceType", "ResourceType");
                String resourceId = text(key, "resourceId", "ResourceId");
                CustomResourceConfig resource = service.getCustomResource(region, resourceType, resourceId);
                if (resource == null) {
                    ObjectNode missed = unprocessed.addObject();
                    missed.put("resourceType", resourceType);
                    missed.put("resourceId", resourceId);
                } else {
                    ObjectNode item = items.addObject();
                    item.put("resourceType", resource.resourceType());
                    item.put("resourceId", resource.resourceId());
                    if (resource.resourceName() != null) {
                        item.put("resourceName", resource.resourceName());
                    }
                    if (resource.configuration() != null) {
                        item.put("configuration", resource.configuration());
                    }
                }
            }
        }
        return Response.ok(resp).build();
    }

    private Response getResourceConfigHistory(JsonNode req, String region) {
        String resourceType = text(req, "resourceType", "ResourceType");
        String resourceId = text(req, "resourceId", "ResourceId");
        CustomResourceConfig resource = service.getCustomResource(region, resourceType, resourceId);
        if (resource == null) {
            throw new io.github.hectorvent.floci.core.common.AwsException(
                    "ResourceNotDiscoveredException",
                    "Resource " + resourceType + "/" + resourceId + " is not discovered.", 400);
        }
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode items = resp.putArray("configurationItems");
        ObjectNode item = items.addObject();
        item.put("resourceType", resource.resourceType());
        item.put("resourceId", resource.resourceId());
        if (resource.configuration() != null) {
            item.put("configuration", resource.configuration());
        }
        return Response.ok(resp).build();
    }

    // --- Compliance reads ---

    private Response describeComplianceByResource() {
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("ComplianceByResources");
        return Response.ok(resp).build();
    }

    private Response getComplianceDetailsByResource() {
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("EvaluationResults");
        return Response.ok(resp).build();
    }

    private Response getComplianceSummaryByConfigRule() {
        ObjectNode resp = mapper.createObjectNode();
        resp.set("ComplianceSummary", emptyComplianceSummary());
        return Response.ok(resp).build();
    }

    private Response getComplianceSummaryByResourceType() {
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("ComplianceSummariesByResourceType");
        return Response.ok(resp).build();
    }

    private Response getComplianceDetailsByConfigRule(JsonNode req, String region) {
        String ruleName = text(req, "ConfigRuleName", "configRuleName");
        service.describeConfigRules(region, ruleName == null ? List.of() : List.of(ruleName));
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("EvaluationResults");
        return Response.ok(resp).build();
    }

    // --- Evaluations ---

    private Response putEvaluations() {
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("FailedEvaluations");
        return Response.ok(resp).build();
    }

    private Response putExternalEvaluation(JsonNode req, String region) {
        String ruleName = text(req, "ConfigRuleName", "configRuleName");
        service.putExternalEvaluation(region, ruleName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response putResourceConfig(JsonNode req, String region) {
        Map<String, String> resourceTags = new java.util.LinkedHashMap<>();
        JsonNode tagsNode = field(req, "Tags", "tags");
        if (tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> resourceTags.put(e.getKey(), e.getValue().asText()));
        }
        service.putResourceConfig(region,
                text(req, "ResourceType", "resourceType"),
                text(req, "SchemaVersionId", "schemaVersionId"),
                text(req, "ResourceId", "resourceId"),
                text(req, "ResourceName", "resourceName"),
                text(req, "Configuration", "configuration"),
                resourceTags);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteResourceConfig(JsonNode req, String region) {
        service.deleteResourceConfig(region,
                text(req, "ResourceType", "resourceType"),
                text(req, "ResourceId", "resourceId"));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response startResourceEvaluation(JsonNode req, String region) {
        JsonNode details = field(req, "ResourceDetails", "resourceDetails");
        StoredResourceEvaluation evaluation = service.startResourceEvaluation(region,
                text(req, "EvaluationMode", "evaluationMode"),
                text(details, "ResourceId", "resourceId"),
                text(details, "ResourceType", "resourceType"),
                text(details, "ResourceConfiguration", "resourceConfiguration"),
                text(details, "ResourceConfigurationSchemaType", "resourceConfigurationSchemaType"),
                text(req, "ClientToken", "clientToken"));
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ResourceEvaluationId", evaluation.resourceEvaluationId());
        return Response.ok(resp).build();
    }

    private Response getResourceEvaluationSummary(JsonNode req, String region) {
        StoredResourceEvaluation evaluation = service.getResourceEvaluation(region,
                text(req, "ResourceEvaluationId", "resourceEvaluationId"));
        ObjectNode resp = mapper.createObjectNode();
        resp.put("ResourceEvaluationId", evaluation.resourceEvaluationId());
        resp.put("EvaluationMode", evaluation.evaluationMode());
        resp.put("EvaluationStartTimestamp", evaluation.evaluationStartTimestamp());
        ObjectNode status = resp.putObject("EvaluationStatus");
        status.put("Status", evaluation.status());
        ObjectNode details = resp.putObject("ResourceDetails");
        details.put("ResourceId", evaluation.resourceId());
        details.put("ResourceType", evaluation.resourceType());
        details.put("ResourceConfiguration", evaluation.resourceConfiguration());
        if (evaluation.resourceConfigurationSchemaType() != null) {
            details.put("ResourceConfigurationSchemaType", evaluation.resourceConfigurationSchemaType());
        }
        return Response.ok(resp).build();
    }

    private Response listResourceEvaluations(JsonNode req, String region) {
        JsonNode filters = field(req, "Filters", "filters");
        String mode = text(filters, "EvaluationMode", "evaluationMode");
        List<StoredResourceEvaluation> evaluations = service.listResourceEvaluations(region, mode);
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode arr = resp.putArray("ResourceEvaluations");
        for (StoredResourceEvaluation evaluation : evaluations) {
            ObjectNode entry = arr.addObject();
            entry.put("ResourceEvaluationId", evaluation.resourceEvaluationId());
            entry.put("EvaluationMode", evaluation.evaluationMode());
            entry.put("EvaluationStartTimestamp", evaluation.evaluationStartTimestamp());
        }
        return Response.ok(resp).build();
    }

    // --- Helpers ---

    private List<String> extractStringList(JsonNode req, String fieldName) {
        List<String> result = new ArrayList<>();
        if (req.has(fieldName)) {
            req.path(fieldName).forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private List<Map<String, String>> extractTags(JsonNode req) {
        List<Map<String, String>> tagList = new ArrayList<>();
        if (req.has("Tags") && req.path("Tags").isArray()) {
            req.path("Tags").forEach(t -> tagList.add(Map.of(
                    "Key", t.path("Key").asText(),
                    "Value", t.path("Value").asText())));
        }
        return tagList;
    }

    private static JsonNode field(JsonNode req, String... names) {
        if (req == null || req.isMissingNode() || req.isNull()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        for (String name : names) {
            if (req.has(name)) {
                return req.get(name);
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static String text(JsonNode req, String... names) {
        JsonNode node = field(req, names);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return (value == null || value.isBlank()) ? null : value;
    }

    private ObjectNode emptyComplianceSummary() {
        ObjectNode summary = mapper.createObjectNode();
        ObjectNode compliant = summary.putObject("CompliantResourceCount");
        compliant.put("CappedCount", 0);
        compliant.put("CapExceeded", false);
        ObjectNode nonCompliant = summary.putObject("NonCompliantResourceCount");
        nonCompliant.put("CappedCount", 0);
        nonCompliant.put("CapExceeded", false);
        return summary;
    }
}
