package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.LogEventsIngested;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetadataService.*;

/** Contributor Insights aggregates only events ingested while a matching rule is enabled. */
@ApplicationScoped
public class CloudWatchInsightsService {
    private final StorageBackend<String, ObjectNode> store;
    private final ObjectMapper mapper;
    private final RegionResolver regions;
    private final CloudWatchMetadataService metadata;

    @Inject
    public CloudWatchInsightsService(StorageFactory factory, ObjectMapper mapper, RegionResolver regions,
                                     CloudWatchMetadataService metadata) {
        this(factory.create("cloudwatchmetrics", "cwinsights.json",
                new TypeReference<Map<String, ObjectNode>>() {}), mapper, regions, metadata);
    }

    CloudWatchInsightsService(StorageBackend<String, ObjectNode> store, ObjectMapper mapper, RegionResolver regions,
                              CloudWatchMetadataService metadata) {
        this.store = store;
        this.mapper = mapper;
        this.regions = regions;
        this.metadata = metadata;
    }

    public ObjectNode put(JsonNode request, String region) {
        String name = required(request, "RuleName");
        String definition = required(request, "RuleDefinition");
        JsonNode parsed = definition(definition);
        if (!"CloudWatchLogRule".equals(parsed.path("Schema").path("Name").asText())
                || parsed.path("Schema").path("Version").asInt() != 1
                || !parsed.path("LogGroupNames").isArray() || parsed.path("LogGroupNames").isEmpty()
                || !parsed.path("Contribution").path("Keys").isArray() || parsed.path("Contribution").path("Keys").isEmpty()) {
            throw invalid("Invalid Contributor Insights rule definition");
        }
        if (!"JSON".equals(parsed.path("LogFormat").asText())) {
            throw invalid("Only JSON Contributor Insights log rules are supported");
        }
        String aggregate = parsed.path("AggregateOn").asText();
        if (!List.of("Count", "Sum").contains(aggregate)) throw invalid("AggregateOn must be Count or Sum");
        for (JsonNode path : parsed.path("Contribution").path("Keys")) validatePath(path.asText());
        if (aggregate.equals("Sum")) validatePath(required(parsed.path("Contribution"), "ValueOf"));
        for (JsonNode filter : parsed.path("Contribution").path("Filters")) {
            validatePath(required(filter, "Match"));
            long conditions = filter.properties().stream().filter(e -> !e.getKey().equals("Match")).count();
            if (conditions != 1) throw invalid("Each contribution filter requires one condition");
            for (var property : filter.properties()) {
                if (!List.of("Match", "In", "NotIn", "StartsWith", "NotStartsWith", "GreaterThan",
                        "GreaterThanOrEqualTo", "LessThan", "LessThanOrEqualTo", "IsPresent").contains(property.getKey())) {
                    throw invalid("Unsupported contribution filter: " + property.getKey());
                }
            }
        }
        String state = request.path("RuleState").asText("ENABLED");
        if (!List.of("ENABLED", "DISABLED").contains(state)) throw invalid("Invalid RuleState");
        ObjectNode previous = store.get(ruleKey(region, name)).orElse(null);
        ObjectNode rule = mapper.createObjectNode().put("Name", name).put("Definition", definition)
                .put("State", state).put("Schema", "CloudWatchLogRule/1").put("ManagedRule", false)
                .put("ApplyOnTransformedLogs", request.path("ApplyOnTransformedLogs").asBoolean(false));
        if (rule.path("ApplyOnTransformedLogs").asBoolean()) throw invalid("Transformed log rules are not supported");
        rule.set("Tags", previous != null ? previous.path("Tags").deepCopy()
                : request.has("Tags") ? request.get("Tags").deepCopy() : mapper.createArrayNode());
        store.put(ruleKey(region, name), rule);
        return mapper.createObjectNode();
    }

    public ObjectNode describe(JsonNode request, String region) {
        List<ObjectNode> rules = store.scan(k -> k.startsWith(region + "::rule::")).stream()
                .sorted(Comparator.comparing(r -> r.path("Name").asText())).map(r -> {
                    ObjectNode copy = r.deepCopy();
                    copy.remove("Tags");
                    return copy;
                }).toList();
        return metadata.page("InsightRules", rules, request);
    }

    public ObjectNode change(JsonNode request, String region, String action) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode failures = response.putArray("Failures");
        if (!request.path("RuleNames").isArray() || request.path("RuleNames").isEmpty()) throw invalid("RuleNames is required");
        for (JsonNode requested : request.get("RuleNames")) {
            String name = requested.asText();
            ObjectNode rule = store.get(ruleKey(region, name)).map(ObjectNode::deepCopy).orElse(null);
            if (rule == null) {
                failures.addObject().put("FailureResource", name).put("ExceptionType", "ResourceNotFoundException")
                        .put("FailureCode", "ResourceNotFoundException").put("FailureDescription", "Rule not found");
            } else if (action.equals("DeleteInsightRules")) {
                store.delete(ruleKey(region, name));
                String prefix = region + "::sample::" + name + "::";
                for (String key : store.keys()) if (key.startsWith(prefix)) store.delete(key);
            } else {
                rule.put("State", action.equals("EnableInsightRules") ? "ENABLED" : "DISABLED");
                store.put(ruleKey(region, name), rule);
            }
        }
        return response;
    }

    public boolean ownsArn(String arn, String region) {
        String prefix = regions.buildArn("cloudwatch", region, "insight-rule/");
        return arn.startsWith(prefix) && store.get(ruleKey(region, arn.substring(prefix.length()))).isPresent();
    }

    public ArrayNode tags(String arn, String region) {
        ObjectNode rule = ruleForArn(arn, region);
        return ((ArrayNode) rule.path("Tags")).deepCopy();
    }

    public void tags(String arn, JsonNode additions, JsonNode removals, String region) {
        ObjectNode rule = ruleForArn(arn, region);
        ArrayNode tags = ((ArrayNode) rule.get("Tags")).deepCopy();
        for (JsonNode addition : additions) {
            for (int i = tags.size() - 1; i >= 0; i--) if (tags.get(i).path("Key").equals(addition.path("Key"))) tags.remove(i);
            tags.add(addition.deepCopy());
        }
        for (int i = tags.size() - 1; i >= 0; i--) if (contains(removals, tags.get(i).path("Key").asText())) tags.remove(i);
        rule.set("Tags", tags);
        store.put(ruleKey(region, rule.path("Name").asText()), rule);
    }

    private ObjectNode ruleForArn(String arn, String region) {
        if (!ownsArn(arn, region)) throw notFound(arn);
        return store.get(ruleKey(region, arn.substring(arn.indexOf(":insight-rule/") + 14))).orElseThrow().deepCopy();
    }

    public void ingest(@Observes LogEventsIngested batch) {
        String prefix = batch.region() + "::rule::";
        for (ObjectNode rule : scan(batch.accountId(), prefix)) {
            if (!"ENABLED".equals(rule.path("State").asText())) continue;
            JsonNode definition = definition(rule.path("Definition").asText());
            boolean matches = false;
            for (JsonNode group : definition.path("LogGroupNames")) {
                if (glob(group.asText(), batch.logGroupName())) matches = true;
            }
            if (!matches) continue;
            for (var event : batch.events()) {
                JsonNode log;
                try {
                    log = mapper.readTree(event.getMessage());
                } catch (JsonProcessingException e) {
                    continue;
                }
                if (log == null || !log.isObject()) continue;
                JsonNode contribution = definition.path("Contribution");
                if (!matchesFilters(log, contribution.path("Filters"))) continue;
                ArrayNode keys = mapper.createArrayNode();
                boolean complete = true;
                for (JsonNode selector : contribution.path("Keys")) {
                    JsonNode value = select(log, selector.asText());
                    if (!value.isValueNode() || value.isNull()) { complete = false; break; }
                    keys.add(value.asText());
                }
                if (!complete) continue;
                double value = 1;
                if ("Sum".equals(definition.path("AggregateOn").asText())) {
                    JsonNode selected = select(log, contribution.path("ValueOf").asText());
                    if (!selected.isNumber()) continue;
                    value = selected.asDouble();
                }
                ObjectNode sample = mapper.createObjectNode().put("Timestamp", event.getTimestamp() / 1000.0).put("Value", value);
                sample.set("Keys", keys);
                String key = batch.region() + "::sample::" + rule.path("Name").asText() + "::" + event.getEventId();
                put(batch.accountId(), key, sample);
            }
        }
    }

    public ObjectNode report(JsonNode request, String region) {
        String name = required(request, "RuleName");
        ObjectNode rule = store.get(ruleKey(region, name)).orElseThrow(() -> notFound(name));
        JsonNode definition = definition(rule.path("Definition").asText());
        if (!request.has("StartTime") || !request.has("EndTime")) throw invalid("StartTime and EndTime are required");
        double start = epoch(request.get("StartTime"));
        double end = epoch(request.get("EndTime"));
        int period = request.path("Period").asInt();
        int max = request.path("MaxContributorCount").asInt(10);
        if (end <= start || period < 1 || max < 1 || max > 100) throw invalid("Invalid report interval or contributor limit");
        Map<String, List<ObjectNode>> contributors = new LinkedHashMap<>();
        Map<Long, List<ObjectNode>> buckets = new TreeMap<>();
        List<ObjectNode> samples = store.scan(k -> k.startsWith(region + "::sample::" + name + "::")).stream()
                .filter(s -> s.path("Timestamp").asDouble() >= start && s.path("Timestamp").asDouble() < end).toList();
        for (ObjectNode sample : samples) {
            contributors.computeIfAbsent(sample.path("Keys").toString(), ignored -> new ArrayList<>()).add(sample);
            long bucket = Math.floorDiv(sample.path("Timestamp").asLong(), period) * period;
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(sample);
        }
        ObjectNode response = mapper.createObjectNode().put("AggregationStatistic", definition.path("AggregateOn").asText())
                .put("AggregateValue", sum(samples)).put("ApproximateUniqueCount", contributors.size());
        response.set("KeyLabels", definition.path("Contribution").path("Keys").deepCopy());
        ArrayNode rows = response.putArray("Contributors");
        Comparator<List<ObjectNode>> rank = Comparator.comparingDouble(CloudWatchInsightsService::sum);
        if ("Maximum".equals(request.path("OrderBy").asText())) {
            rank = Comparator.comparingDouble(s -> s.stream().mapToDouble(v -> v.path("Value").asDouble()).max().orElse(0));
        }
        contributors.values().stream().sorted(rank.reversed()).limit(max).forEach(samplesForKey -> {
            ObjectNode row = rows.addObject().put("ApproximateAggregateValue", sum(samplesForKey));
            row.set("Keys", samplesForKey.getFirst().get("Keys").deepCopy());
            Map<Long, Double> points = new TreeMap<>();
            for (ObjectNode sample : samplesForKey) {
                long bucket = Math.floorDiv(sample.path("Timestamp").asLong(), period) * period;
                points.merge(bucket, sample.path("Value").asDouble(), Double::sum);
            }
            ArrayNode datapoints = row.putArray("Datapoints");
            points.forEach((time, value) -> datapoints.addObject().put("Timestamp", time).put("ApproximateValue", value));
        });
        ArrayNode datapoints = response.putArray("MetricDatapoints");
        buckets.forEach((timestamp, values) -> {
            ObjectNode point = datapoints.addObject().put("Timestamp", timestamp);
            Map<String, Double> totals = new LinkedHashMap<>();
            values.forEach(v -> totals.merge(v.path("Keys").toString(), v.path("Value").asDouble(), Double::sum));
            for (JsonNode metric : request.path("Metrics")) {
                double value = switch (metric.asText()) {
                    case "UniqueContributors" -> totals.size();
                    case "MaxContributorValue" -> totals.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                    case "SampleCount" -> values.size();
                    case "Sum" -> sum(values);
                    case "Average" -> sum(values) / values.size();
                    case "Minimum" -> values.stream().mapToDouble(v -> v.path("Value").asDouble()).min().orElse(0);
                    case "Maximum" -> values.stream().mapToDouble(v -> v.path("Value").asDouble()).max().orElse(0);
                    default -> throw invalid("Unsupported report metric: " + metric.asText());
                };
                point.put(metric.asText(), value);
            }
        });
        return response;
    }

    private static double sum(List<ObjectNode> values) { return values.stream().mapToDouble(v -> v.path("Value").asDouble()).sum(); }

    private boolean matchesFilters(JsonNode log, JsonNode filters) {
        for (JsonNode filter : filters) {
            JsonNode value = select(log, filter.path("Match").asText());
            if (filter.has("IsPresent")) {
                if (filter.get("IsPresent").asBoolean() == value.isMissingNode()) return false;
                continue;
            }
            if (value.isMissingNode()) return false;
            if (filter.has("In") && !contains(filter.get("In"), value.asText())) return false;
            if (filter.has("NotIn") && contains(filter.get("NotIn"), value.asText())) return false;
            for (String op : List.of("StartsWith", "NotStartsWith")) {
                if (!filter.has(op)) continue;
                boolean found = false;
                for (JsonNode prefix : filter.get(op)) if (value.asText().startsWith(prefix.asText())) found = true;
                if (op.equals("StartsWith") != found) return false;
            }
            for (String op : List.of("GreaterThan", "GreaterThanOrEqualTo", "LessThan", "LessThanOrEqualTo")) {
                if (!filter.has(op)) continue;
                if (!value.isNumber()) return false;
                double number = value.asDouble(), threshold = filter.get(op).asDouble();
                boolean matches = switch (op) {
                    case "GreaterThan" -> number > threshold;
                    case "GreaterThanOrEqualTo" -> number >= threshold;
                    case "LessThan" -> number < threshold;
                    default -> number <= threshold;
                };
                if (!matches) return false;
            }
        }
        return true;
    }

    private static void validatePath(String path) {
        if (!path.matches("\\$\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) {
            throw invalid("Unsupported JSON selector: " + path);
        }
    }

    private JsonNode select(JsonNode log, String path) {
        JsonNode node = log;
        for (String field : path.substring(2).split("\\.")) node = node.path(field);
        return node;
    }

    private static boolean glob(String pattern, String name) {
        String regex = java.util.Arrays.stream(pattern.split("\\*", -1)).map(java.util.regex.Pattern::quote)
                .collect(java.util.stream.Collectors.joining(".*"));
        return name.matches(regex);
    }

    private JsonNode definition(String text) {
        try {
            JsonNode value = mapper.readTree(text);
            if (value == null || !value.isObject()) throw invalid("RuleDefinition must be an object");
            return value;
        } catch (JsonProcessingException e) {
            throw invalid("RuleDefinition must be valid JSON");
        }
    }

    private List<ObjectNode> scan(String account, String prefix) {
        if (account != null && store instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<ObjectNode> partition = (AccountAwareStorageBackend<ObjectNode>) aware;
            return partition.scanForAccount(account, k -> k.startsWith(prefix));
        }
        return store.scan(k -> k.startsWith(prefix));
    }

    private void put(String account, String key, ObjectNode value) {
        if (account != null && store instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<ObjectNode> partition = (AccountAwareStorageBackend<ObjectNode>) aware;
            partition.putForAccount(account, key, value);
        } else store.put(key, value);
    }

    private static String ruleKey(String region, String name) { return region + "::rule::" + name; }
}
