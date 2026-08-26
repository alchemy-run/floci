package io.github.hectorvent.floci.services.xray;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.xray.model.XRayGroup;
import io.github.hectorvent.floci.services.xray.model.XRayResourcePolicy;
import io.github.hectorvent.floci.services.xray.model.XRaySamplingRule;
import io.github.hectorvent.floci.services.xray.model.XRaySegment;
import io.github.hectorvent.floci.services.xray.model.XRayTrace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AWS X-Ray restJson1 — traces, sampling, groups, insights, and Transaction Search
 * stubs used by Alchemy bindings.
 */
@ApplicationScoped
public class XRayService {

    static final String SERVICE = "xray";
    static final String DEFAULT_GROUP = "Default";
    static final String DEFAULT_RULE = "Default";

    private static final Pattern SERVICE_FILTER =
            Pattern.compile("service\\s*\\(\\s*\"([^\"]+)\"\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_POLICIES = 5;

    private final StorageBackend<String, XRayTrace> traces;
    private final StorageBackend<String, XRayGroup> groups;
    private final StorageBackend<String, XRaySamplingRule> rules;
    private final StorageBackend<String, XRayResourcePolicy> policies;
    private final StorageBackend<String, Map<String, Integer>> statistics;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public XRayService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(
                storageFactory.create(SERVICE, "xray-traces.json", new TypeReference<Map<String, XRayTrace>>() {
                }),
                storageFactory.create(SERVICE, "xray-groups.json", new TypeReference<Map<String, XRayGroup>>() {
                }),
                storageFactory.create(SERVICE, "xray-sampling-rules.json",
                        new TypeReference<Map<String, XRaySamplingRule>>() {
                        }),
                storageFactory.create(SERVICE, "xray-resource-policies.json",
                        new TypeReference<Map<String, XRayResourcePolicy>>() {
                        }),
                storageFactory.create(SERVICE, "xray-sampling-stats.json",
                        new TypeReference<Map<String, Map<String, Integer>>>() {
                        }),
                regionResolver,
                objectMapper);
    }

    XRayService(
            StorageBackend<String, XRayTrace> traces,
            StorageBackend<String, XRayGroup> groups,
            StorageBackend<String, XRaySamplingRule> rules,
            StorageBackend<String, XRayResourcePolicy> policies,
            StorageBackend<String, Map<String, Integer>> statistics,
            RegionResolver regionResolver,
            ObjectMapper objectMapper) {
        this.traces = traces;
        this.groups = groups;
        this.rules = rules;
        this.policies = policies;
        this.statistics = statistics;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode putTraceSegments(String region, JsonNode request) {
        JsonNode documents = request.get("TraceSegmentDocuments");
        if (documents == null || !documents.isArray()) {
            throw invalidRequest("TraceSegmentDocuments must be an array.");
        }
        List<String> docs = new ArrayList<>();
        for (JsonNode node : documents) {
            if (!node.isTextual()) {
                throw invalidRequest("TraceSegmentDocuments members must be strings.");
            }
            docs.add(node.textValue());
        }
        List<ObjectNode> unprocessed = putTraceDocuments(region, docs);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode unprocessedNode = response.putArray("UnprocessedTraceSegments");
        unprocessed.forEach(unprocessedNode::add);
        return response;
    }

    /**
     * Records a function-level segment for a Lambda invocation with Active tracing.
     * The segment {@code name} is the function name so {@code service("name")} matches.
     */
    public synchronized void recordLambdaInvocation(String region, String functionName, String functionArn) {
        if (functionName == null || functionName.isBlank()) {
            return;
        }
        double now = nowEpoch();
        String traceId = newTraceId(now);
        String segmentId = randomHex(16);
        ObjectNode segment = objectMapper.createObjectNode();
        segment.put("name", functionName);
        segment.put("id", segmentId);
        segment.put("trace_id", traceId);
        segment.put("start_time", now - 0.05);
        segment.put("end_time", now);
        segment.put("origin", "AWS::Lambda::Function");
        if (functionArn != null && !functionArn.isBlank()) {
            segment.putObject("aws").put("function_arn", functionArn);
        }
        putTraceDocuments(region, List.of(segment.toString()));
    }

    public ObjectNode getTraceSummaries(String region, JsonNode request) {
        double start = requireEpoch(request, "StartTime");
        double end = requireEpoch(request, "EndTime");
        if (end < start) {
            throw invalidRequest("EndTime must be greater than or equal to StartTime.");
        }
        String filter = textOrNull(request, "FilterExpression");
        String serviceName = serviceFilter(filter);
        ensureDefaults(region);
        List<XRayTrace> matches = traces.scan(key -> key.startsWith(region + "::")).stream()
                .filter(trace -> overlaps(trace, start, end))
                .filter(trace -> serviceName == null || trace.getServiceNames().contains(serviceName))
                .sorted(Comparator.comparingDouble(XRayTrace::getStartTime).reversed())
                .toList();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("TraceSummaries");
        for (XRayTrace trace : matches) {
            ObjectNode summary = summaries.addObject();
            summary.put("Id", trace.getId());
            summary.put("Duration", trace.duration());
        }
        response.put("ApproximateTime", end);
        response.put("TracesProcessedCount", matches.size());
        return response;
    }

    public ObjectNode batchGetTraces(String region, JsonNode request) {
        JsonNode idsNode = request.get("TraceIds");
        if (idsNode == null || !idsNode.isArray()) {
            throw invalidRequest("TraceIds must be an array.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tracesNode = response.putArray("Traces");
        ArrayNode unprocessed = response.putArray("UnprocessedTraceIds");
        for (JsonNode idNode : idsNode) {
            if (!idNode.isTextual() || idNode.textValue().isBlank()) {
                continue;
            }
            String id = idNode.textValue();
            XRayTrace trace = traces.get(traceKey(region, id)).orElse(null);
            if (trace == null) {
                unprocessed.add(id);
                continue;
            }
            tracesNode.add(traceNode(trace));
        }
        return response;
    }

    public ObjectNode putTelemetryRecords(JsonNode request) {
        JsonNode records = request.get("TelemetryRecords");
        if (records == null || !records.isArray() || records.isEmpty()) {
            throw invalidRequest("TelemetryRecords is required.");
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode getSamplingRules(String region, JsonNode request) {
        ensureDefaults(region);
        List<XRaySamplingRule> items = rules.scan(key -> key.startsWith(region + "::")).stream()
                .sorted(Comparator.comparingInt(XRaySamplingRule::getPriority)
                        .thenComparing(XRaySamplingRule::getRuleName, Comparator.nullsLast(String::compareTo)))
                .toList();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode records = response.putArray("SamplingRuleRecords");
        for (XRaySamplingRule rule : items) {
            records.add(samplingRecord(rule));
        }
        return response;
    }

    public synchronized ObjectNode getSamplingTargets(String region, JsonNode request) {
        ensureDefaults(region);
        JsonNode documents = request.get("SamplingStatisticsDocuments");
        if (documents == null || !documents.isArray()) {
            throw invalidRequest("SamplingStatisticsDocuments must be an array.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode targets = response.putArray("SamplingTargetDocuments");
        ArrayNode unprocessed = response.putArray("UnprocessedStatistics");
        double now = nowEpoch();
        for (JsonNode document : documents) {
            String ruleName = textOrNull(document, "RuleName");
            if (ruleName == null) {
                continue;
            }
            XRaySamplingRule rule = rules.get(ruleKey(region, ruleName)).orElse(null);
            if (rule == null) {
                ObjectNode missed = unprocessed.addObject();
                missed.put("RuleName", ruleName);
                missed.put("ErrorCode", "404");
                missed.put("Message", "Sampling rule not found.");
                continue;
            }
            recordStatistic(region, ruleName, document);
            ObjectNode target = targets.addObject();
            target.put("RuleName", rule.getRuleName());
            target.put("FixedRate", rule.getFixedRate());
            target.put("ReservoirQuota", rule.getReservoirSize());
            target.put("ReservoirQuotaTTL", now + 10);
            target.put("Interval", 10);
        }
        response.putArray("UnprocessedBoostStatistics");
        response.put("LastRuleModification", now);
        return response;
    }

    public ObjectNode getSamplingStatisticSummaries(String region, JsonNode request) {
        ensureDefaults(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("SamplingStatisticSummaries");
        String prefix = region + "::stat::";
        double now = nowEpoch();
        for (String key : statistics.keys()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            Map<String, Integer> counts = statistics.get(key).orElse(Map.of());
            ObjectNode summary = summaries.addObject();
            summary.put("RuleName", key.substring(prefix.length()));
            summary.put("Timestamp", now);
            summary.put("RequestCount", counts.getOrDefault("RequestCount", 0));
            summary.put("SampledCount", counts.getOrDefault("SampledCount", 0));
            summary.put("BorrowCount", counts.getOrDefault("BorrowCount", 0));
        }
        return response;
    }

    public ObjectNode getServiceGraph(String region, JsonNode request) {
        double start = requireEpoch(request, "StartTime");
        double end = requireEpoch(request, "EndTime");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StartTime", start);
        response.put("EndTime", end);
        ArrayNode services = response.putArray("Services");
        int ref = 0;
        for (String name : distinctServices(region, start, end, null)) {
            ObjectNode service = services.addObject();
            service.put("ReferenceId", ref++);
            service.put("Name", name);
            service.putArray("Names").add(name);
            service.put("Type", "AWS::Lambda::Function");
        }
        response.put("ContainsOldGroupVersions", false);
        return response;
    }

    public ObjectNode getTraceGraph(String region, JsonNode request) {
        JsonNode idsNode = request.get("TraceIds");
        if (idsNode == null || !idsNode.isArray()) {
            throw invalidRequest("TraceIds must be an array.");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : idsNode) {
            if (id.isTextual()) {
                ids.add(id.textValue());
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode services = response.putArray("Services");
        int ref = 0;
        for (String name : distinctServices(region, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, ids)) {
            ObjectNode service = services.addObject();
            service.put("ReferenceId", ref++);
            service.put("Name", name);
            service.putArray("Names").add(name);
        }
        return response;
    }

    public ObjectNode getTimeSeriesServiceStatistics(String region, JsonNode request) {
        requireEpoch(request, "StartTime");
        requireEpoch(request, "EndTime");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("TimeSeriesServiceStatistics");
        response.put("ContainsOldGroupVersions", false);
        return response;
    }

    public ObjectNode getInsightSummaries(String region, JsonNode request) {
        requireEpoch(request, "StartTime");
        requireEpoch(request, "EndTime");
        ensureDefaults(region);
        String groupName = textOrNull(request, "GroupName");
        String groupArn = textOrNull(request, "GroupARN");
        if (groupName != null || groupArn != null) {
            requireGroup(region, groupName, groupArn);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("InsightSummaries");
        return response;
    }

    public ObjectNode getInsight(String region, JsonNode request) {
        requireText(request, "InsightId");
        throw invalidRequest("Insight not found.");
    }

    public ObjectNode getInsightEvents(String region, JsonNode request) {
        requireText(request, "InsightId");
        throw invalidRequest("Insight not found.");
    }

    public ObjectNode getInsightImpactGraph(String region, JsonNode request) {
        requireText(request, "InsightId");
        requireEpoch(request, "StartTime");
        requireEpoch(request, "EndTime");
        throw invalidRequest("Insight not found.");
    }

    public ObjectNode getTraceSegmentDestination() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Destination", "XRay");
        response.put("Status", "ACTIVE");
        return response;
    }

    public ObjectNode startTraceRetrieval(JsonNode request) {
        JsonNode ids = request.get("TraceIds");
        if (ids == null || !ids.isArray() || ids.isEmpty()) {
            throw invalidRequest("TraceIds is required.");
        }
        requireEpoch(request, "StartTime");
        requireEpoch(request, "EndTime");
        throw invalidRequest("Transaction Search is not enabled for this account.");
    }

    public ObjectNode listRetrievedTraces(JsonNode request) {
        requireText(request, "RetrievalToken");
        throw notFound("Retrieval job not found.");
    }

    public ObjectNode getRetrievedTracesGraph(JsonNode request) {
        requireText(request, "RetrievalToken");
        throw notFound("Retrieval job not found.");
    }

    public ObjectNode cancelTraceRetrieval(JsonNode request) {
        requireText(request, "RetrievalToken");
        throw notFound("Retrieval job not found.");
    }

    public synchronized ObjectNode createGroup(String region, JsonNode request) {
        ensureDefaults(region);
        String name = requireText(request, "GroupName");
        if (DEFAULT_GROUP.equals(name)) {
            throw invalidRequest("Group Default already exists");
        }
        if (groups.get(groupKey(region, name)).isPresent()) {
            throw invalidRequest("Group " + name + " already exists");
        }
        XRayGroup group = new XRayGroup();
        group.setGroupName(name);
        group.setGroupArn(groupArn(region, name));
        group.setFilterExpression(textOrDefault(request, "FilterExpression", "*"));
        JsonNode insights = request.get("InsightsConfiguration");
        if (insights != null && insights.isObject()) {
            group.setInsightsEnabled(insights.path("InsightsEnabled").asBoolean(false));
            group.setNotificationsEnabled(insights.path("NotificationsEnabled").asBoolean(false));
        }
        group.setTags(readTags(request.get("Tags")));
        groups.put(groupKey(region, name), group);
        return wrap("Group", groupNode(group));
    }

    public ObjectNode getGroup(String region, JsonNode request) {
        ensureDefaults(region);
        XRayGroup group = requireGroup(region, textOrNull(request, "GroupName"), textOrNull(request, "GroupARN"));
        return wrap("Group", groupNode(group));
    }

    public synchronized ObjectNode updateGroup(String region, JsonNode request) {
        ensureDefaults(region);
        XRayGroup group = requireGroup(region, textOrNull(request, "GroupName"), textOrNull(request, "GroupARN"));
        String filter = textOrNull(request, "FilterExpression");
        if (filter != null) {
            group.setFilterExpression(filter);
        }
        JsonNode insights = request.get("InsightsConfiguration");
        if (insights != null && insights.isObject()) {
            if (insights.has("InsightsEnabled")) {
                group.setInsightsEnabled(insights.get("InsightsEnabled").asBoolean());
            }
            if (insights.has("NotificationsEnabled")) {
                group.setNotificationsEnabled(insights.get("NotificationsEnabled").asBoolean());
            }
        }
        groups.put(groupKey(region, group.getGroupName()), group);
        return wrap("Group", groupNode(group));
    }

    public synchronized ObjectNode deleteGroup(String region, JsonNode request) {
        ensureDefaults(region);
        XRayGroup group = requireGroup(region, textOrNull(request, "GroupName"), textOrNull(request, "GroupARN"));
        if (DEFAULT_GROUP.equals(group.getGroupName())) {
            throw invalidRequest("The Default group cannot be deleted.");
        }
        groups.delete(groupKey(region, group.getGroupName()));
        return objectMapper.createObjectNode();
    }

    public ObjectNode getGroups(String region, JsonNode request) {
        ensureDefaults(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("Groups");
        groups.scan(key -> key.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(XRayGroup::getGroupName, Comparator.nullsLast(String::compareTo)))
                .forEach(group -> summaries.add(groupNode(group)));
        return response;
    }

    public synchronized ObjectNode createSamplingRule(String region, JsonNode request) {
        ensureDefaults(region);
        JsonNode ruleNode = request.get("SamplingRule");
        if (ruleNode == null || !ruleNode.isObject()) {
            throw invalidRequest("SamplingRule is required.");
        }
        String name = textOrNull(ruleNode, "RuleName");
        if (name == null) {
            throw invalidRequest("SamplingRule.RuleName is required.");
        }
        if (DEFAULT_RULE.equals(name) || rules.get(ruleKey(region, name)).isPresent()) {
            throw invalidRequest("Sampling rule already exists");
        }
        XRaySamplingRule rule = fromRuleNode(region, name, ruleNode);
        double now = nowEpoch();
        rule.setCreatedAt(now);
        rule.setModifiedAt(now);
        rule.setTags(readTags(request.get("Tags")));
        rules.put(ruleKey(region, name), rule);
        return wrap("SamplingRuleRecord", samplingRecord(rule));
    }

    public synchronized ObjectNode updateSamplingRule(String region, JsonNode request) {
        ensureDefaults(region);
        JsonNode update = request.get("SamplingRuleUpdate");
        if (update == null || !update.isObject()) {
            throw invalidRequest("SamplingRuleUpdate is required.");
        }
        String name = textOrNull(update, "RuleName");
        String arn = textOrNull(update, "RuleARN");
        XRaySamplingRule rule = requireRule(region, name, arn);
        applyRuleUpdate(rule, update);
        rule.setModifiedAt(nowEpoch());
        rules.put(ruleKey(region, rule.getRuleName()), rule);
        return wrap("SamplingRuleRecord", samplingRecord(rule));
    }

    public synchronized ObjectNode deleteSamplingRule(String region, JsonNode request) {
        ensureDefaults(region);
        XRaySamplingRule rule = requireRule(region, textOrNull(request, "RuleName"), textOrNull(request, "RuleARN"));
        if (DEFAULT_RULE.equals(rule.getRuleName())) {
            throw invalidRequest("The Default sampling rule cannot be deleted.");
        }
        rules.delete(ruleKey(region, rule.getRuleName()));
        return wrap("SamplingRuleRecord", samplingRecord(rule));
    }

    public ObjectNode getEncryptionConfig() {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("Type", "NONE");
        config.put("Status", "ACTIVE");
        return wrap("EncryptionConfig", config);
    }

    public ObjectNode putEncryptionConfig(JsonNode request) {
        String type = textOrDefault(request, "Type", "NONE");
        ObjectNode config = objectMapper.createObjectNode();
        config.put("Type", type);
        config.put("Status", "ACTIVE");
        if (request.hasNonNull("KeyId")) {
            config.put("KeyId", request.get("KeyId").asText());
        }
        return wrap("EncryptionConfig", config);
    }

    public synchronized ObjectNode putResourcePolicy(String region, JsonNode request) {
        String name = requireText(request, "PolicyName");
        String document = requireText(request, "PolicyDocument");
        XRayResourcePolicy existing = policies.get(policyKey(region, name)).orElse(null);
        String revision = textOrNull(request, "PolicyRevisionId");
        if (existing != null && revision != null && !revision.equals(existing.getPolicyRevisionId())) {
            throw new AwsException(
                    "InvalidPolicyRevisionIdException",
                    "The provided policy revision id does not match.",
                    400);
        }
        if (existing == null && policies.scan(key -> key.startsWith(region + "::")).size() >= MAX_POLICIES) {
            throw new AwsException("PolicyCountLimitExceededException",
                    "A maximum of 5 resource policies can be created.", 400);
        }
        XRayResourcePolicy policy = existing == null ? new XRayResourcePolicy() : existing;
        policy.setPolicyName(name);
        policy.setPolicyDocument(document);
        policy.setPolicyRevisionId(UUID.randomUUID().toString());
        policy.setLastUpdatedTime(nowEpoch());
        policies.put(policyKey(region, name), policy);
        return wrap("ResourcePolicy", policyNode(policy));
    }

    public synchronized ObjectNode deleteResourcePolicy(String region, JsonNode request) {
        String name = requireText(request, "PolicyName");
        XRayResourcePolicy policy = policies.get(policyKey(region, name)).orElse(null);
        if (policy == null) {
            return objectMapper.createObjectNode();
        }
        String revision = textOrNull(request, "PolicyRevisionId");
        if (revision != null && !revision.equals(policy.getPolicyRevisionId())) {
            throw new AwsException("InvalidPolicyRevisionIdException",
                    "The provided policy revision id does not match.", 400);
        }
        policies.delete(policyKey(region, name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listResourcePolicies(String region, JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("ResourcePolicies");
        policies.scan(key -> key.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(XRayResourcePolicy::getPolicyName,
                        Comparator.nullsLast(String::compareTo)))
                .forEach(policy -> items.add(policyNode(policy)));
        return response;
    }

    public ObjectNode listTagsForResource(String region, JsonNode request) {
        Map<String, String> tags = tagged(region, requireText(request, "ResourceARN")).tags();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Tags");
        tags.forEach((key, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return response;
    }

    public synchronized ObjectNode tagResource(String region, JsonNode request) {
        String arn = requireText(request, "ResourceARN");
        Map<String, String> incoming = readTags(request.get("Tags"));
        Tagged tagged = tagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        current.putAll(incoming);
        if (current.size() > 50) {
            throw new AwsException("TooManyTagsException", "A resource can have at most 50 tags.", 400);
        }
        tagged.applyTags(current);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(String region, JsonNode request) {
        String arn = requireText(request, "ResourceARN");
        JsonNode keys = request.get("TagKeys");
        if (keys == null || !keys.isArray()) {
            throw invalidRequest("TagKeys must be an array.");
        }
        Tagged tagged = tagged(region, arn);
        Map<String, String> current = new LinkedHashMap<>(tagged.tags());
        for (JsonNode key : keys) {
            if (key.isTextual()) {
                current.remove(key.textValue());
            }
        }
        tagged.applyTags(current);
        return objectMapper.createObjectNode();
    }

    public ObjectNode getIndexingRules() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("IndexingRules");
        return response;
    }

    public ObjectNode updateIndexingRule(JsonNode request) {
        throw invalidRequest("Indexing rule not found.");
    }

    public ObjectNode updateTraceSegmentDestination(JsonNode request) {
        String destination = textOrDefault(request, "Destination", "XRay");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Destination", destination);
        response.put("Status", "ACTIVE");
        return response;
    }

    private List<ObjectNode> putTraceDocuments(String region, List<String> documents) {
        List<ObjectNode> unprocessed = new ArrayList<>();
        for (String document : documents) {
            try {
                JsonNode parsed = objectMapper.readTree(document);
                String traceId = textOrNull(parsed, "trace_id");
                String segmentId = textOrNull(parsed, "id");
                if (traceId == null || segmentId == null) {
                    unprocessed.add(unprocessedSegment(segmentId, "Missing trace_id or id."));
                    continue;
                }
                double start = parsed.path("start_time").asDouble(nowEpoch());
                double end = parsed.path("end_time").asDouble(start);
                String name = textOrNull(parsed, "name");
                String key = traceKey(region, traceId);
                XRayTrace trace = traces.get(key).orElseGet(XRayTrace::new);
                if (trace.getId() == null) {
                    trace.setId(traceId);
                    trace.setStartTime(start);
                    trace.setEndTime(end);
                } else {
                    trace.setStartTime(Math.min(trace.getStartTime(), start));
                    trace.setEndTime(Math.max(trace.getEndTime(), end));
                }
                trace.getSegments().add(new XRaySegment(segmentId, document));
                if (name != null) {
                    trace.getServiceNames().add(name);
                }
                traces.put(key, trace);
            } catch (Exception e) {
                unprocessed.add(unprocessedSegment(null, "Segment is not valid JSON."));
            }
        }
        return unprocessed;
    }

    private void recordStatistic(String region, String ruleName, JsonNode document) {
        String key = region + "::stat::" + ruleName;
        Map<String, Integer> counts = new LinkedHashMap<>(statistics.get(key).orElse(Map.of()));
        counts.put("RequestCount", counts.getOrDefault("RequestCount", 0)
                + document.path("RequestCount").asInt(0));
        counts.put("SampledCount", counts.getOrDefault("SampledCount", 0)
                + document.path("SampledCount").asInt(0));
        counts.put("BorrowCount", counts.getOrDefault("BorrowCount", 0)
                + document.path("BorrowCount").asInt(0));
        statistics.put(key, counts);
    }

    private List<String> distinctServices(String region, double start, double end, List<String> ids) {
        List<XRayTrace> candidates;
        if (ids != null) {
            candidates = new ArrayList<>();
            for (String id : ids) {
                traces.get(traceKey(region, id)).ifPresent(candidates::add);
            }
        } else {
            candidates = traces.scan(key -> key.startsWith(region + "::")).stream()
                    .filter(trace -> overlaps(trace, start, end))
                    .toList();
        }
        List<String> names = new ArrayList<>();
        for (XRayTrace trace : candidates) {
            for (String name : trace.getServiceNames()) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private void ensureDefaults(String region) {
        if (groups.get(groupKey(region, DEFAULT_GROUP)).isEmpty()) {
            XRayGroup group = new XRayGroup();
            group.setGroupName(DEFAULT_GROUP);
            group.setGroupArn(groupArn(region, DEFAULT_GROUP));
            group.setFilterExpression("*");
            groups.put(groupKey(region, DEFAULT_GROUP), group);
        }
        if (rules.get(ruleKey(region, DEFAULT_RULE)).isEmpty()) {
            XRaySamplingRule rule = new XRaySamplingRule();
            rule.setRuleName(DEFAULT_RULE);
            rule.setRuleArn(ruleArn(region, DEFAULT_RULE));
            double now = nowEpoch();
            rule.setCreatedAt(now);
            rule.setModifiedAt(now);
            rules.put(ruleKey(region, DEFAULT_RULE), rule);
        }
    }

    private XRayGroup requireGroup(String region, String name, String arn) {
        if (name == null && arn != null) {
            name = groupNameFromArn(arn);
        }
        if (name == null) {
            throw invalidRequest("GroupName or GroupARN is required.");
        }
        return groups.get(groupKey(region, name))
                .orElseThrow(() -> invalidRequest("Group not found"));
    }

    private XRaySamplingRule requireRule(String region, String name, String arn) {
        if (name == null && arn != null) {
            name = ruleNameFromArn(arn);
        }
        if (name == null) {
            throw invalidRequest("RuleName or RuleARN is required.");
        }
        return rules.get(ruleKey(region, name))
                .orElseThrow(() -> invalidRequest("Sampling rule does not exist"));
    }

    private Tagged tagged(String region, String arn) {
        if (arn.contains(":group/")) {
            XRayGroup group = requireGroup(region, null, arn);
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return group.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    group.setTags(tags);
                    groups.put(groupKey(region, group.getGroupName()), group);
                }
            };
        }
        if (arn.contains(":sampling-rule/")) {
            String name = ruleNameFromArn(arn);
            XRaySamplingRule rule = name == null ? null : rules.get(ruleKey(region, name)).orElse(null);
            if (rule == null) {
                throw notFound("Resource not found: " + arn);
            }
            XRaySamplingRule found = rule;
            return new Tagged() {
                @Override
                public Map<String, String> tags() {
                    return found.getTags();
                }

                @Override
                public void applyTags(Map<String, String> tags) {
                    found.setTags(tags);
                    rules.put(ruleKey(region, found.getRuleName()), found);
                }
            };
        }
        throw notFound("Resource not found: " + arn);
    }

    private ObjectNode traceNode(XRayTrace trace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", trace.getId());
        node.put("Duration", trace.duration());
        node.put("LimitExceeded", false);
        ArrayNode segments = node.putArray("Segments");
        for (XRaySegment segment : trace.getSegments()) {
            ObjectNode item = segments.addObject();
            item.put("Id", segment.getId());
            item.put("Document", segment.getDocument());
        }
        return node;
    }

    private ObjectNode groupNode(XRayGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("GroupName", group.getGroupName());
        node.put("GroupARN", group.getGroupArn());
        if (group.getFilterExpression() != null) {
            node.put("FilterExpression", group.getFilterExpression());
        }
        ObjectNode insights = node.putObject("InsightsConfiguration");
        insights.put("InsightsEnabled", group.isInsightsEnabled());
        insights.put("NotificationsEnabled", group.isNotificationsEnabled());
        return node;
    }

    private ObjectNode samplingRecord(XRaySamplingRule rule) {
        ObjectNode record = objectMapper.createObjectNode();
        record.set("SamplingRule", samplingRuleNode(rule));
        record.put("CreatedAt", rule.getCreatedAt());
        record.put("ModifiedAt", rule.getModifiedAt());
        return record;
    }

    private ObjectNode samplingRuleNode(XRaySamplingRule rule) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("RuleName", rule.getRuleName());
        node.put("RuleARN", rule.getRuleArn());
        node.put("ResourceARN", rule.getResourceArn());
        node.put("Priority", rule.getPriority());
        node.put("FixedRate", rule.getFixedRate());
        node.put("ReservoirSize", rule.getReservoirSize());
        node.put("ServiceName", rule.getServiceName());
        node.put("ServiceType", rule.getServiceType());
        node.put("Host", rule.getHost());
        node.put("HTTPMethod", rule.getHttpMethod());
        node.put("URLPath", rule.getUrlPath());
        node.put("Version", rule.getVersion());
        ObjectNode attributes = node.putObject("Attributes");
        rule.getAttributes().forEach(attributes::put);
        return node;
    }

    private ObjectNode policyNode(XRayResourcePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PolicyName", policy.getPolicyName());
        node.put("PolicyDocument", policy.getPolicyDocument());
        node.put("PolicyRevisionId", policy.getPolicyRevisionId());
        node.put("LastUpdatedTime", policy.getLastUpdatedTime());
        return node;
    }

    private XRaySamplingRule fromRuleNode(String region, String name, JsonNode node) {
        XRaySamplingRule rule = new XRaySamplingRule();
        rule.setRuleName(name);
        rule.setRuleArn(ruleArn(region, name));
        applyRuleUpdate(rule, node);
        if (node.hasNonNull("Version")) {
            rule.setVersion(node.get("Version").asInt());
        }
        return rule;
    }

    private void applyRuleUpdate(XRaySamplingRule rule, JsonNode node) {
        if (node.hasNonNull("ResourceARN")) {
            rule.setResourceArn(node.get("ResourceARN").asText());
        }
        if (node.hasNonNull("Priority")) {
            rule.setPriority(node.get("Priority").asInt());
        }
        if (node.hasNonNull("FixedRate")) {
            rule.setFixedRate(node.get("FixedRate").asDouble());
        }
        if (node.hasNonNull("ReservoirSize")) {
            rule.setReservoirSize(node.get("ReservoirSize").asInt());
        }
        if (node.hasNonNull("ServiceName")) {
            rule.setServiceName(node.get("ServiceName").asText());
        }
        if (node.hasNonNull("ServiceType")) {
            rule.setServiceType(node.get("ServiceType").asText());
        }
        if (node.hasNonNull("Host")) {
            rule.setHost(node.get("Host").asText());
        }
        if (node.hasNonNull("HTTPMethod")) {
            rule.setHttpMethod(node.get("HTTPMethod").asText());
        }
        if (node.hasNonNull("URLPath")) {
            rule.setUrlPath(node.get("URLPath").asText());
        }
        JsonNode attributes = node.get("Attributes");
        if (attributes != null && attributes.isObject()) {
            Map<String, String> map = new LinkedHashMap<>();
            attributes.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    map.put(entry.getKey(), entry.getValue().textValue());
                }
            });
            rule.setAttributes(map);
        }
    }

    private ObjectNode wrap(String field, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, value);
        return response;
    }

    private ObjectNode unprocessedSegment(String id, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        if (id != null) {
            node.put("Id", id);
        }
        node.put("ErrorCode", "Invalid");
        node.put("Message", message);
        return node;
    }

    private static boolean overlaps(XRayTrace trace, double start, double end) {
        return trace.getStartTime() <= end && trace.getEndTime() >= start;
    }

    private static String serviceFilter(String expression) {
        if (expression == null || expression.isBlank() || "*".equals(expression.trim())) {
            return null;
        }
        Matcher matcher = SERVICE_FILTER.matcher(expression);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String groupArn(String region, String name) {
        String id = name.equals(DEFAULT_GROUP) ? "3e4e8884034c" : randomHex(12);
        return "arn:aws:xray:" + region + ":" + regionResolver.getAccountId() + ":group/" + name + "/" + id;
    }

    private String ruleArn(String region, String name) {
        return "arn:aws:xray:" + region + ":" + regionResolver.getAccountId() + ":sampling-rule/" + name;
    }

    private static String groupNameFromArn(String arn) {
        int group = arn.indexOf(":group/");
        if (group < 0) {
            return null;
        }
        String rest = arn.substring(group + ":group/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static String ruleNameFromArn(String arn) {
        int idx = arn.indexOf(":sampling-rule/");
        return idx < 0 ? null : arn.substring(idx + ":sampling-rule/".length());
    }

    private static String traceKey(String region, String id) {
        return region + "::trace::" + id;
    }

    private static String groupKey(String region, String name) {
        return region + "::group::" + name;
    }

    private static String ruleKey(String region, String name) {
        return region + "::rule::" + name;
    }

    private static String policyKey(String region, String name) {
        return region + "::policy::" + name;
    }

    private static String newTraceId(double now) {
        long epoch = (long) now;
        return "1-" + Long.toHexString(epoch) + "-" + randomHex(24);
    }

    private static String randomHex(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(Integer.toHexString(random.nextInt(16)));
        }
        return builder.toString();
    }

    private static double nowEpoch() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static double requireEpoch(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            throw invalidRequest(field + " is required.");
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            try {
                return Double.parseDouble(value.textValue());
            } catch (NumberFormatException e) {
                throw invalidRequest(field + " must be an epoch timestamp.");
            }
        }
        throw invalidRequest(field + " must be an epoch timestamp.");
    }

    private static String requireText(JsonNode request, String field) {
        String value = textOrNull(request, field);
        if (value == null) {
            throw invalidRequest(field + " is required.");
        }
        return value;
    }

    private static String textOrDefault(JsonNode request, String field, String fallback) {
        String value = textOrNull(request, field);
        return value == null ? fallback : value;
    }

    private static String textOrNull(JsonNode request, String field) {
        if (request == null) {
            return null;
        }
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
            return null;
        }
        return value.textValue();
    }

    private static Map<String, String> readTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode == null || tagsNode.isNull()) {
            return tags;
        }
        if (!tagsNode.isArray()) {
            throw invalidRequest("Tags must be an array.");
        }
        for (JsonNode entry : tagsNode) {
            JsonNode key = entry.get("Key");
            JsonNode value = entry.get("Value");
            if (key == null || !key.isTextual() || value == null || !value.isTextual()) {
                throw invalidRequest("Tags entries must have Key and Value.");
            }
            tags.put(key.textValue(), value.textValue());
        }
        return tags;
    }

    private static AwsException invalidRequest(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private interface Tagged {
        Map<String, String> tags();

        void applyTags(Map<String, String> tags);
    }
}
