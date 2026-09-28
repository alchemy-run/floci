package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Account-partitioned CloudWatch configuration and alarm history. */
@ApplicationScoped
public class CloudWatchMetadataService {
    private final StorageBackend<String, ObjectNode> store;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private final CloudWatchMetricsService metrics;

    @Inject
    public CloudWatchMetadataService(StorageFactory factory, RegionResolver regions,
                                    ObjectMapper mapper, CloudWatchMetricsService metrics) {
        this(factory.create("cloudwatchmetrics", "cwmetadata.json",
                new TypeReference<Map<String, ObjectNode>>() {}), regions, mapper, metrics);
    }

    CloudWatchMetadataService(StorageBackend<String, ObjectNode> store, RegionResolver regions,
                             ObjectMapper mapper, CloudWatchMetricsService metrics) {
        this.store = store;
        this.regions = regions;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public ObjectNode putCompositeAlarm(JsonNode request, String region) {
        String name = required(request, "AlarmName");
        String expression = required(request, "AlarmRule");
        new AlarmRule(expression, ignored -> "INSUFFICIENT_DATA").evaluate();
        if (!metrics.describeAlarms(List.of(name), null, region).isEmpty()) {
            throw invalid("An alarm of a different type already has this name");
        }
        String key = key(region, "composite", name);
        ObjectNode previous = store.get(key).orElse(null);
        ObjectNode alarm = ((ObjectNode) request).deepCopy();
        alarm.put("AlarmArn", regions.buildArn("cloudwatch", region, "alarm:" + name));
        alarm.put("ActionsEnabled", request.path("ActionsEnabled").asBoolean(true));
        for (String field : List.of("AlarmActions", "OKActions", "InsufficientDataActions")) {
            if (!alarm.has(field)) alarm.putArray(field);
        }
        if (previous != null) alarm.set("Tags", previous.path("Tags").deepCopy());
        else if (!alarm.has("Tags")) alarm.putArray("Tags");
        alarm.put("StateValue", previous == null ? "INSUFFICIENT_DATA" : previous.path("StateValue").asText());
        alarm.put("StateUpdatedTimestamp", previous == null ? now() : previous.path("StateUpdatedTimestamp").asDouble());
        alarm.put("AlarmConfigurationUpdatedTimestamp", now());
        store.put(key, alarm);
        history(name, "CompositeAlarm", "ConfigurationUpdate", "Alarm configuration updated", alarm, region);
        return mapper.createObjectNode();
    }

    public List<ObjectNode> compositeAlarms(JsonNode request, String region) {
        List<ObjectNode> alarms = rows(region, "composite");
        for (ObjectNode alarm : alarms) refreshComposite(alarm, region, new HashSet<>());
        return alarms.stream().filter(a -> alarmMatches(a, request)).map(a -> {
            ObjectNode copy = a.deepCopy();
            copy.remove("Tags");
            return copy;
        }).toList();
    }

    private String refreshComposite(ObjectNode alarm, String region, HashSet<String> visiting) {
        String name = alarm.path("AlarmName").asText();
        if (!visiting.add(name)) throw invalid("Composite alarm cycle involving " + name);
        boolean active = new AlarmRule(alarm.path("AlarmRule").asText(), reference -> {
            String referencedName = reference.contains(":alarm:")
                    ? reference.substring(reference.indexOf(":alarm:") + 7) : reference;
            if (reference.startsWith("arn:") && !reference.equals(
                    regions.buildArn("cloudwatch", region, "alarm:" + referencedName))) {
                throw invalid("Alarm references must belong to the same account and region");
            }
            ObjectNode composite = store.get(key(region, "composite", referencedName)).orElse(null);
            if (composite != null) return refreshComposite(composite, region, visiting);
            var found = metrics.describeAlarms(List.of(referencedName), null, region);
            return found.isEmpty() ? null : found.getFirst().getStateValue();
        }).evaluate();
        visiting.remove(name);
        String state = active ? "ALARM" : "OK";
        if (!state.equals(alarm.path("StateValue").asText())) {
            alarm.put("StateValue", state);
            alarm.put("StateReason", "AlarmRule evaluated to " + active);
            alarm.put("StateUpdatedTimestamp", now());
            store.put(key(region, "composite", name), alarm);
            history(name, "CompositeAlarm", "StateUpdate", alarm.path("StateReason").asText(), alarm, region);
        }
        return state;
    }

    static boolean alarmMatches(JsonNode alarm, JsonNode request) {
        return (!request.path("AlarmNames").isArray() || request.path("AlarmNames").isEmpty()
                || contains(request.path("AlarmNames"), alarm.path("AlarmName").asText()))
                && (!request.has("AlarmNamePrefix") || alarm.path("AlarmName").asText()
                    .startsWith(request.path("AlarmNamePrefix").asText()))
                && (!request.has("StateValue") || request.path("StateValue").equals(alarm.path("StateValue")))
                && (!request.has("ActionPrefix") || hasActionPrefix(alarm, request.path("ActionPrefix").asText()));
    }

    private static boolean hasActionPrefix(JsonNode alarm, String prefix) {
        for (String field : List.of("AlarmActions", "OKActions", "InsufficientDataActions")) {
            for (JsonNode action : alarm.path(field)) if (action.asText().startsWith(prefix)) return true;
        }
        return false;
    }

    public boolean isComposite(String name, String region) {
        return store.get(key(region, "composite", name)).isPresent();
    }

    public void deleteComposite(String name, String region) {
        store.get(key(region, "composite", name)).ifPresent(alarm -> {
            history(name, "CompositeAlarm", "ConfigurationUpdate", "Alarm deleted", alarm, region);
            store.delete(key(region, "composite", name));
        });
    }

    public void setCompositeState(JsonNode request, String region) {
        String name = required(request, "AlarmName");
        ObjectNode alarm = get(region, "composite", name);
        alarm.put("StateValue", required(request, "StateValue"));
        alarm.put("StateReason", required(request, "StateReason"));
        if (request.has("StateReasonData")) alarm.set("StateReasonData", request.get("StateReasonData"));
        alarm.put("StateUpdatedTimestamp", now());
        store.put(key(region, "composite", name), alarm);
        history(name, "CompositeAlarm", "StateUpdate", alarm.path("StateReason").asText(), alarm, region);
    }

    public void setActions(List<String> names, boolean enabled, String region) {
        if (names.isEmpty()) throw invalid("AlarmNames is required");
        for (String name : names) {
            if (!isComposite(name, region) && metrics.describeAlarms(List.of(name), null, region).isEmpty()) {
                throw notFound(name);
            }
        }
        for (String name : names) {
            if (isComposite(name, region)) {
                ObjectNode alarm = get(region, "composite", name);
                alarm.put("ActionsEnabled", enabled);
                store.put(key(region, "composite", name), alarm);
                history(name, "CompositeAlarm", "ConfigurationUpdate", "Actions enabled: " + enabled, alarm, region);
            } else {
                metrics.setAlarmActions(name, enabled, region);
                history(name, "MetricAlarm", "ConfigurationUpdate", "Actions enabled: " + enabled,
                        mapper.createObjectNode().put("ActionsEnabled", enabled), region);
            }
        }
    }

    public void history(String name, String alarmType, String type, String summary, JsonNode data, String region) {
        ObjectNode entry = mapper.createObjectNode().put("AlarmName", name).put("AlarmType", alarmType)
                .put("HistoryItemType", type).put("HistorySummary", summary)
                .put("HistoryData", data.toString()).put("Timestamp", now());
        store.put(key(region, "history", UUID.randomUUID().toString()), entry);
    }

    public ObjectNode describeHistory(JsonNode request, String region) {
        var items = rows(region, "history").stream()
                .filter(e -> !request.has("AlarmName") || request.path("AlarmName").equals(e.path("AlarmName")))
                .filter(e -> !request.has("HistoryItemType") || request.path("HistoryItemType").equals(e.path("HistoryItemType")))
                .filter(e -> !request.has("AlarmTypes") || contains(request.path("AlarmTypes"), e.path("AlarmType").asText()))
                .filter(e -> !request.has("StartDate") || e.path("Timestamp").asDouble() >= epoch(request.get("StartDate")))
                .filter(e -> !request.has("EndDate") || e.path("Timestamp").asDouble() < epoch(request.get("EndDate")))
                .sorted(Comparator.comparingDouble((ObjectNode e) -> e.path("Timestamp").asDouble())
                        .reversed()).toList();
        if ("TimestampAscending".equals(request.path("ScanBy").asText())) items = items.reversed();
        return page("AlarmHistoryItems", items, request);
    }

    public ObjectNode putDetector(JsonNode request, String region) {
        ObjectNode identity = detectorIdentity(request);
        ObjectNode detector = identity.deepCopy();
        for (String field : List.of("Configuration", "MetricCharacteristics")) {
            if (request.has(field)) detector.set(field, request.get(field).deepCopy());
        }
        // A configured detector without a trained statistical model is pending training.
        detector.put("StateValue", "PENDING_TRAINING");
        store.put(key(region, "detector", identity.toString()), detector);
        return mapper.createObjectNode();
    }

    public ObjectNode deleteDetector(JsonNode request, String region) {
        String key = key(region, "detector", detectorIdentity(request).toString());
        if (store.get(key).isEmpty()) throw notFound("Anomaly detector");
        store.delete(key);
        return mapper.createObjectNode();
    }

    public ObjectNode describeDetectors(JsonNode request, String region) {
        var result = rows(region, "detector").stream().filter(d -> {
            JsonNode single = d.path("SingleMetricAnomalyDetector");
            if (request.has("AnomalyDetectorTypes") && !contains(request.get("AnomalyDetectorTypes"),
                    single.isMissingNode() ? "METRIC_MATH" : "SINGLE_METRIC")) return false;
            for (String field : List.of("Namespace", "MetricName")) {
                if (request.has(field) && !request.get(field).equals(single.path(field))) return false;
            }
            if (request.has("Dimensions")) {
                for (JsonNode dimension : request.get("Dimensions")) {
                    boolean found = false;
                    for (JsonNode actual : single.path("Dimensions")) if (actual.equals(dimension)) found = true;
                    if (!found) return false;
                }
            }
            return true;
        }).toList();
        return page("AnomalyDetectors", result, request);
    }

    private ObjectNode detectorIdentity(JsonNode request) {
        ObjectNode identity = mapper.createObjectNode();
        if (request.has("MetricMathAnomalyDetector")) {
            if (request.has("SingleMetricAnomalyDetector") || request.has("Namespace") || request.has("MetricName")) {
                throw invalid("Specify one anomaly detector identity");
            }
            JsonNode math = request.get("MetricMathAnomalyDetector");
            if (!math.path("MetricDataQueries").isArray() || math.path("MetricDataQueries").isEmpty()) {
                throw invalid("MetricDataQueries is required");
            }
            identity.set("MetricMathAnomalyDetector", canonical(math));
        } else {
            JsonNode source = request.has("SingleMetricAnomalyDetector") ? request.get("SingleMetricAnomalyDetector") : request;
            ObjectNode single = mapper.createObjectNode();
            for (String field : List.of("Namespace", "MetricName", "Stat")) single.put(field, required(source, field));
            if (source.has("AccountId")) single.set("AccountId", source.get("AccountId"));
            List<JsonNode> dims = new ArrayList<>();
            source.path("Dimensions").forEach(d -> dims.add(canonical(d)));
            dims.sort(Comparator.comparing(JsonNode::toString));
            single.set("Dimensions", mapper.valueToTree(dims));
            identity.set("SingleMetricAnomalyDetector", single);
        }
        return identity;
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> result.set(e.getKey(), canonical(e.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(n -> result.add(canonical(n)));
            return result;
        }
        return node.deepCopy();
    }

    public ObjectNode putMuteRule(JsonNode request, String region) {
        String name = required(request, "Name");
        JsonNode schedule = request.path("Rule").path("Schedule");
        String expression = required(schedule, "Expression");
        lastScheduleStart(expression, Instant.now());
        String duration = required(schedule, "Duration");
        try {
            if (java.time.Duration.parse(duration).isNegative() || java.time.Duration.parse(duration).isZero()) {
                throw invalid("Duration must be positive");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw invalid("Duration must be an ISO-8601 duration");
        }
        ObjectNode rule = ((ObjectNode) request).deepCopy();
        rule.put("AlarmMuteRuleArn", regions.buildArn("cloudwatch", region, "alarm-mute-rule:" + name));
        rule.put("LastUpdatedTimestamp", now());
        for (String field : List.of("StartDate", "ExpireDate")) if (rule.has(field)) rule.put(field, epoch(rule.get(field)));
        if (rule.has("StartDate") && rule.has("ExpireDate")
                && rule.path("StartDate").asDouble() >= rule.path("ExpireDate").asDouble()) {
            throw invalid("ExpireDate must be after StartDate");
        }
        store.put(key(region, "mute", name), rule);
        return mapper.createObjectNode().put("AlarmMuteRuleArn", rule.path("AlarmMuteRuleArn").asText());
    }

    public ObjectNode getMuteRule(JsonNode request, String region) {
        return muteDescription(get(region, "mute", required(request, "AlarmMuteRuleName")));
    }

    private ObjectNode muteDescription(ObjectNode stored) {
        ObjectNode rule = stored.deepCopy();
        rule.remove("Tags");
        Instant current = Instant.now();
        double timestamp = current.toEpochMilli() / 1000.0;
        if (rule.has("ExpireDate") && epoch(rule.get("ExpireDate")) <= timestamp) {
            rule.put("Status", "EXPIRED");
        } else {
            JsonNode schedule = rule.path("Rule").path("Schedule");
            Instant start = lastScheduleStart(schedule.path("Expression").asText(), current);
            boolean active = start != null
                    && start.plus(java.time.Duration.parse(schedule.path("Duration").asText())).isAfter(current)
                    && (!rule.has("StartDate") || start.toEpochMilli() / 1000.0 >= epoch(rule.get("StartDate")));
            rule.put("Status", active ? "ACTIVE" : "SCHEDULED");
        }
        return rule;
    }

    public boolean muted(String alarmName, String region) {
        return rows(region, "mute").stream().map(this::muteDescription)
                .anyMatch(r -> "ACTIVE".equals(r.path("Status").asText())
                        && contains(r.path("MuteTargets").path("AlarmNames"), alarmName));
    }

    private Instant lastScheduleStart(String expression, Instant current) {
        try {
            if (expression.startsWith("at(") && expression.endsWith(")")) {
                String value = expression.substring(3, expression.length() - 1);
                Instant start = value.endsWith("Z") ? Instant.parse(value)
                        : java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC);
                return start.isAfter(current) ? null : start;
            }
            String cronText = expression.startsWith("cron(") && expression.endsWith(")")
                    ? expression.substring(5, expression.length() - 1) : expression;
            com.cronutils.model.Cron cron;
            if (cronText.trim().split("\\s+").length == 5) {
                cron = new com.cronutils.parser.CronParser(com.cronutils.model.definition.CronDefinitionBuilder
                        .instanceDefinitionFor(com.cronutils.model.CronType.UNIX)).parse(cronText);
            } else {
                cron = io.github.hectorvent.floci.core.common.AwsCronDefinitions.newParser().parse("0 " + cronText);
            }
            cron.validate();
            return com.cronutils.model.time.ExecutionTime.forCron(cron)
                    .lastExecution(current.plusNanos(1).atZone(java.time.ZoneOffset.UTC))
                    .map(java.time.ZonedDateTime::toInstant).orElse(null);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw invalid("Invalid mute schedule expression: " + expression);
        }
    }

    public ObjectNode listMuteRules(JsonNode request, String region) {
        var rules = rows(region, "mute").stream().map(this::muteDescription)
                .filter(r -> !request.has("Statuses") || contains(request.get("Statuses"), r.path("Status").asText()))
                .filter(r -> !request.has("AlarmName") || contains(r.path("MuteTargets").path("AlarmNames"),
                        request.path("AlarmName").asText())).map(r -> {
                    r.retain("AlarmMuteRuleArn", "ExpireDate", "Status", "MuteType", "LastUpdatedTimestamp");
                    return r;
                }).toList();
        return page("AlarmMuteRuleSummaries", rules, request);
    }

    public ObjectNode deleteMuteRule(JsonNode request, String region) {
        String name = required(request, "AlarmMuteRuleName");
        get(region, "mute", name);
        store.delete(key(region, "mute", name));
        return mapper.createObjectNode();
    }

    public boolean ownsArn(String arn, String region) {
        return resourceByArn(arn, region) != null;
    }

    public ArrayNode tags(String arn, String region) {
        ObjectNode resource = resourceByArn(arn, region);
        if (resource == null) throw notFound(arn);
        return resource.has("Tags") ? ((ArrayNode) resource.get("Tags")).deepCopy() : mapper.createArrayNode();
    }

    public void tags(String arn, JsonNode additions, JsonNode removals, String region) {
        ObjectNode resource = resourceByArn(arn, region);
        if (resource == null) throw notFound(arn);
        ArrayNode tags = tags(arn, region);
        for (JsonNode addition : additions) {
            for (int i = tags.size() - 1; i >= 0; i--) {
                if (tags.get(i).path("Key").equals(addition.path("Key"))) tags.remove(i);
            }
            tags.add(addition.deepCopy());
        }
        for (int i = tags.size() - 1; i >= 0; i--) {
            if (contains(removals, tags.get(i).path("Key").asText())) tags.remove(i);
        }
        resource.set("Tags", tags);
        String kind = resource.has("AlarmName") ? "composite" : "mute";
        store.put(key(region, kind, resource.path(kind.equals("composite") ? "AlarmName" : "Name").asText()), resource);
    }

    private ObjectNode resourceByArn(String arn, String region) {
        return store.scan(k -> k.startsWith(region + "::composite::") || k.startsWith(region + "::mute::"))
                .stream().filter(r -> arn.equals(r.path("AlarmArn").asText()) || arn.equals(r.path("AlarmMuteRuleArn").asText()))
                .findFirst().map(ObjectNode::deepCopy).orElse(null);
    }

    private ObjectNode get(String region, String kind, String name) {
        return store.get(key(region, kind, name)).map(ObjectNode::deepCopy).orElseThrow(() -> notFound(name));
    }

    private List<ObjectNode> rows(String region, String kind) {
        return store.scan(k -> k.startsWith(key(region, kind, ""))).stream().map(ObjectNode::deepCopy)
                .sorted(Comparator.comparing(JsonNode::toString)).toList();
    }

    ObjectNode page(String field, List<ObjectNode> rows, JsonNode request) {
        int max = request.path("MaxRecords").asInt(request.path("MaxResults").asInt(100));
        if (max < 1 || max > 500) throw invalid("Page size must be between 1 and 500");
        int start;
        try {
            start = request.hasNonNull("NextToken") ? Integer.parseInt(request.get("NextToken").asText()) : 0;
        } catch (NumberFormatException e) {
            throw invalid("Invalid NextToken");
        }
        if (start < 0 || start > rows.size()) throw invalid("Invalid NextToken");
        int end = Math.min(rows.size(), start + max);
        ObjectNode result = mapper.createObjectNode();
        result.set(field, mapper.valueToTree(rows.subList(start, end)));
        if (end < rows.size()) result.put("NextToken", Integer.toString(end));
        return result;
    }

    static String required(JsonNode request, String field) {
        String value = request.path(field).asText("");
        if (value.isBlank()) throw invalid(field + " is required");
        return value;
    }

    static boolean contains(JsonNode array, String value) {
        for (JsonNode node : array) if (node.asText().equals(value)) return true;
        return false;
    }

    static double epoch(JsonNode value) {
        if (value.isNumber()) return value.asDouble();
        try {
            return Instant.parse(value.asText()).toEpochMilli() / 1000.0;
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return Double.parseDouble(value.asText());
            } catch (NumberFormatException ignored) {
                throw invalid("Invalid timestamp: " + value.asText());
            }
        }
    }

    private static String key(String region, String kind, String name) { return region + "::" + kind + "::" + name; }
    private static double now() { return Instant.now().toEpochMilli() / 1000.0; }
    static AwsException invalid(String message) { return new AwsException("InvalidParameterValue", message, 400); }
    static AwsException notFound(String name) { return new AwsException("ResourceNotFoundException", "Resource not found: " + name, 404); }

    /** Recursive descent keeps precedence and quoted alarm names intact. */
    private static final class AlarmRule {
        private final String expression;
        private final Function<String, String> state;
        private int position;

        AlarmRule(String expression, Function<String, String> state) {
            this.expression = expression;
            this.state = state;
        }

        boolean evaluate() {
            boolean result = or();
            whitespace();
            if (position != expression.length()) throw invalid("Invalid AlarmRule at " + position);
            return result;
        }

        private boolean or() {
            boolean result = and();
            while (token("OR")) result = and() | result;
            return result;
        }

        private boolean and() {
            boolean result = atom();
            while (token("AND")) result = atom() & result;
            return result;
        }

        private boolean atom() {
            if (token("NOT")) return !atom();
            if (token("TRUE")) return true;
            if (token("FALSE")) return false;
            if (token("(")) {
                boolean value = or();
                expect(")");
                return value;
            }
            String expected = null;
            for (String candidate : List.of("ALARM", "OK", "INSUFFICIENT_DATA")) {
                if (token(candidate)) { expected = candidate; break; }
            }
            if (expected == null) throw invalid("Invalid AlarmRule at " + position);
            expect("(");
            whitespace();
            String name;
            if (position < expression.length() && expression.charAt(position) == '"') {
                position++;
                StringBuilder text = new StringBuilder();
                while (position < expression.length() && expression.charAt(position) != '"') {
                    char c = expression.charAt(position++);
                    if (c == '\\' && position < expression.length()) c = expression.charAt(position++);
                    text.append(c);
                }
                expect("\"");
                name = text.toString();
            } else {
                int end = expression.indexOf(')', position);
                if (end < 0) throw invalid("Unclosed alarm reference");
                name = expression.substring(position, end).trim();
                position = end;
            }
            expect(")");
            return expected.equals(state.apply(name));
        }

        private void expect(String text) { if (!token(text)) throw invalid("Expected " + text + " in AlarmRule"); }
        private void whitespace() { while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) position++; }
        private boolean token(String text) {
            whitespace();
            if (!expression.startsWith(text, position)) return false;
            int end = position + text.length();
            if (Character.isLetter(text.charAt(0)) && end < expression.length()
                    && (Character.isLetterOrDigit(expression.charAt(end)) || expression.charAt(end) == '_')) return false;
            position = end;
            return true;
        }
    }
}
