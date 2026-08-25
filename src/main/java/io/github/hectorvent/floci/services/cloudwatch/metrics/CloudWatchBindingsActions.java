package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.InsightRule;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.0 / Query encoding for CloudWatch binding operations used by
 * Alchemy Bindings.test.ts. Kept out of the shared metrics handlers so
 * other CloudWatch TDD agents can rewrite those files without dropping
 * these operations.
 */
public final class CloudWatchBindingsActions {

    private static final byte[] TRANSPARENT_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private CloudWatchBindingsActions() {}

    public static boolean handles(String action) {
        return switch (action) {
            case "GetMetricWidgetImage",
                 "DescribeAlarmsForMetric",
                 "DescribeAlarmHistory",
                 "DescribeAlarmContributors",
                 "DisableAlarmActions",
                 "EnableAlarmActions",
                 "GetInsightRuleReport",
                 "DisableInsightRules",
                 "EnableInsightRules",
                 "ListManagedInsightRules",
                 "PutInsightRule",
                 "DescribeInsightRules",
                 "DeleteInsightRules" -> true;
            default -> false;
        };
    }

    public static Response handleJson(ObjectMapper mapper, String action, JsonNode request, String region) {
        CloudWatchMetricsService service = CDI.current().select(CloudWatchMetricsService.class).get();
        return handleJson(service, mapper, action, request, region);
    }

    public static Response handleJson(CloudWatchMetricsService service, ObjectMapper mapper,
                                      String action, JsonNode request, String region) {
        return switch (action) {
            case "PutInsightRule" -> {
                InsightRule rule = new InsightRule();
                String name = textOrNull(request, "RuleName");
                if (name == null) {
                    name = textOrNull(request, "Name");
                }
                rule.setName(name);
                rule.setState(textOrNull(request, "RuleState"));
                JsonNode definitionNode = request.path("RuleDefinition");
                if (definitionNode.isTextual()) {
                    rule.setDefinition(definitionNode.asText());
                } else if (definitionNode.isObject() || definitionNode.isArray()) {
                    rule.setDefinition(definitionNode.toString());
                }
                if (request.has("ApplyOnTransformedLogs")) {
                    rule.setApplyOnTransformedLogs(request.path("ApplyOnTransformedLogs").asBoolean(false));
                }
                service.putInsightRule(rule, region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            case "DescribeInsightRules" -> {
                Integer maxResults = request.has("MaxResults") ? request.path("MaxResults").asInt() : null;
                CloudWatchMetricsService.InsightRulesPage page =
                        service.describeInsightRules(maxResults, textOrNull(request, "NextToken"), region);
                ObjectNode response = mapper.createObjectNode();
                ArrayNode rules = response.putArray("InsightRules");
                for (InsightRule rule : page.rules()) {
                    ObjectNode node = rules.addObject();
                    node.put("Name", rule.getName());
                    node.put("State", rule.getState());
                    node.put("Schema", rule.getSchema());
                    if (rule.getDefinition() != null) {
                        node.put("Definition", rule.getDefinition());
                    }
                    node.put("ManagedRule", rule.isManagedRule());
                    node.put("ApplyOnTransformedLogs", rule.isApplyOnTransformedLogs());
                }
                if (page.nextToken() != null) {
                    response.put("NextToken", page.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "DeleteInsightRules" -> {
                List<Map<String, String>> failures = service.deleteInsightRules(
                        stringList(request.path("RuleNames")), region);
                ObjectNode response = mapper.createObjectNode();
                ArrayNode arr = response.putArray("Failures");
                for (Map<String, String> failure : failures) {
                    ObjectNode node = arr.addObject();
                    failure.forEach(node::put);
                }
                yield Response.ok(response).build();
            }
            case "GetMetricWidgetImage" -> {
                ObjectNode response = mapper.createObjectNode();
                response.put("MetricWidgetImage", TRANSPARENT_PNG);
                yield Response.ok(response).build();
            }
            case "DescribeAlarmsForMetric" -> {
                Integer period = request.has("Period") ? request.path("Period").asInt() : null;
                List<MetricAlarm> alarms = service.describeAlarmsForMetric(
                        textOrNull(request, "Namespace"),
                        textOrNull(request, "MetricName"),
                        textOrNull(request, "Statistic"),
                        period,
                        parseDimensions(request.path("Dimensions")),
                        region);
                ObjectNode response = mapper.createObjectNode();
                ArrayNode arr = response.putArray("MetricAlarms");
                for (MetricAlarm alarm : alarms) {
                    ObjectNode node = arr.addObject();
                    node.put("AlarmName", alarm.getAlarmName());
                    if (alarm.getAlarmArn() != null) node.put("AlarmArn", alarm.getAlarmArn());
                    if (alarm.getMetricName() != null) node.put("MetricName", alarm.getMetricName());
                    if (alarm.getNamespace() != null) node.put("Namespace", alarm.getNamespace());
                    if (alarm.getStatistic() != null) node.put("Statistic", alarm.getStatistic());
                    node.put("Period", alarm.getPeriod());
                    node.put("ActionsEnabled", alarm.isActionsEnabled());
                    if (alarm.getStateValue() != null) node.put("StateValue", alarm.getStateValue());
                }
                yield Response.ok(response).build();
            }
            case "DescribeAlarmHistory" -> {
                Integer maxRecords = request.has("MaxRecords") ? request.path("MaxRecords").asInt() : null;
                List<AlarmHistoryItem> items = service.describeAlarmHistory(
                        textOrNull(request, "AlarmName"), maxRecords, region);
                ObjectNode response = mapper.createObjectNode();
                ArrayNode arr = response.putArray("AlarmHistoryItems");
                for (AlarmHistoryItem item : items) {
                    ObjectNode node = arr.addObject();
                    node.put("AlarmName", item.getAlarmName());
                    node.put("AlarmType", item.getAlarmType());
                    node.put("Timestamp", item.getTimestamp());
                    node.put("HistoryItemType", item.getHistoryItemType());
                    if (item.getHistorySummary() != null) {
                        node.put("HistorySummary", item.getHistorySummary());
                    }
                }
                yield Response.ok(response).build();
            }
            case "DescribeAlarmContributors" ->
                    throw new AwsException("ValidationException", "", 400);
            case "DisableAlarmActions" -> {
                service.setAlarmActionsEnabled(stringList(request.path("AlarmNames")), false, region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            case "EnableAlarmActions" -> {
                service.setAlarmActionsEnabled(stringList(request.path("AlarmNames")), true, region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            case "GetInsightRuleReport" -> {
                InsightRule rule = service.requireInsightRule(textOrNull(request, "RuleName"), region);
                ObjectNode response = mapper.createObjectNode();
                response.putArray("KeyLabels");
                String aggregation = "Sum";
                if (rule.getDefinition() != null && rule.getDefinition().contains("\"AggregateOn\":\"Count\"")) {
                    aggregation = "Count";
                }
                response.put("AggregationStatistic", aggregation);
                response.putArray("Contributors");
                response.putArray("MetricDatapoints");
                yield Response.ok(response).build();
            }
            case "DisableInsightRules" -> insightRuleState(service, mapper, request, region, "DISABLED");
            case "EnableInsightRules" -> insightRuleState(service, mapper, request, region, "ENABLED");
            case "ListManagedInsightRules" -> {
                ObjectNode response = mapper.createObjectNode();
                response.putArray("ManagedRules");
                yield Response.ok(response).build();
            }
            default -> throw new IllegalArgumentException("Unsupported CloudWatch binding action: " + action);
        };
    }

    public static Response handleQuery(String action, MultivaluedMap<String, String> params, String region) {
        CloudWatchMetricsService service = CDI.current().select(CloudWatchMetricsService.class).get();
        return handleQuery(service, action, params, region);
    }

    public static Response handleQuery(CloudWatchMetricsService service, String action,
                                       MultivaluedMap<String, String> params, String region) {
        return switch (action) {
            case "GetMetricWidgetImage" -> {
                String result = new XmlBuilder()
                        .elem("MetricWidgetImage", Base64.getEncoder().encodeToString(TRANSPARENT_PNG))
                        .build();
                yield Response.ok(AwsQueryResponse.envelope("GetMetricWidgetImage", AwsNamespaces.CW, result)).build();
            }
            case "DescribeAlarmsForMetric" -> {
                Integer period = parseInt(params.getFirst("Period"));
                List<Dimension> dimensions = new ArrayList<>();
                for (int i = 1; ; i++) {
                    String name = params.getFirst("Dimensions.member." + i + ".Name");
                    if (name == null) break;
                    dimensions.add(new Dimension(name, params.getFirst("Dimensions.member." + i + ".Value")));
                }
                List<MetricAlarm> alarms = service.describeAlarmsForMetric(
                        params.getFirst("Namespace"),
                        params.getFirst("MetricName"),
                        params.getFirst("Statistic"),
                        period,
                        dimensions,
                        region);
                XmlBuilder xml = new XmlBuilder().start("MetricAlarms");
                for (MetricAlarm alarm : alarms) {
                    xml.start("member")
                            .elem("AlarmName", alarm.getAlarmName())
                            .elem("AlarmArn", alarm.getAlarmArn())
                            .elem("MetricName", alarm.getMetricName())
                            .elem("Namespace", alarm.getNamespace())
                            .elem("Statistic", alarm.getStatistic())
                            .elem("Period", String.valueOf(alarm.getPeriod()))
                            .end("member");
                }
                xml.end("MetricAlarms");
                yield Response.ok(AwsQueryResponse.envelope("DescribeAlarmsForMetric", AwsNamespaces.CW, xml.build())).build();
            }
            case "DescribeAlarmHistory" -> {
                Integer maxRecords = parseInt(params.getFirst("MaxRecords"));
                List<AlarmHistoryItem> items = service.describeAlarmHistory(
                        params.getFirst("AlarmName"), maxRecords, region);
                XmlBuilder xml = new XmlBuilder().start("AlarmHistoryItems");
                for (AlarmHistoryItem item : items) {
                    xml.start("member")
                            .elem("AlarmName", item.getAlarmName())
                            .elem("AlarmType", item.getAlarmType())
                            .elem("Timestamp", Instant.ofEpochSecond(item.getTimestamp()).toString())
                            .elem("HistoryItemType", item.getHistoryItemType())
                            .elem("HistorySummary", item.getHistorySummary())
                            .end("member");
                }
                xml.end("AlarmHistoryItems");
                yield Response.ok(AwsQueryResponse.envelope("DescribeAlarmHistory", AwsNamespaces.CW, xml.build())).build();
            }
            case "DescribeAlarmContributors" ->
                    throw new AwsException("ValidationException", "", 400);
            case "DisableAlarmActions" -> {
                service.setAlarmActionsEnabled(memberList(params, "AlarmNames.member."), false, region);
                yield Response.ok(AwsQueryResponse.envelopeNoResult("DisableAlarmActions", AwsNamespaces.CW)).build();
            }
            case "EnableAlarmActions" -> {
                service.setAlarmActionsEnabled(memberList(params, "AlarmNames.member."), true, region);
                yield Response.ok(AwsQueryResponse.envelopeNoResult("EnableAlarmActions", AwsNamespaces.CW)).build();
            }
            case "GetInsightRuleReport" -> {
                service.requireInsightRule(params.getFirst("RuleName"), region);
                String result = new XmlBuilder()
                        .start("KeyLabels").end("KeyLabels")
                        .elem("AggregationStatistic", "Count")
                        .start("Contributors").end("Contributors")
                        .build();
                yield Response.ok(AwsQueryResponse.envelope("GetInsightRuleReport", AwsNamespaces.CW, result)).build();
            }
            case "DisableInsightRules" -> insightRuleStateQuery(service, params, region, "DISABLED");
            case "EnableInsightRules" -> insightRuleStateQuery(service, params, region, "ENABLED");
            case "ListManagedInsightRules" -> {
                String result = new XmlBuilder().start("ManagedRules").end("ManagedRules").build();
                yield Response.ok(AwsQueryResponse.envelope("ListManagedInsightRules", AwsNamespaces.CW, result)).build();
            }
            default -> throw new IllegalArgumentException("Unsupported CloudWatch binding action: " + action);
        };
    }

    private static Response insightRuleState(CloudWatchMetricsService service, ObjectMapper mapper,
                                             JsonNode request, String region, String state) {
        List<Map<String, String>> failures = service.setInsightRulesState(
                stringList(request.path("RuleNames")), state, region);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode arr = response.putArray("Failures");
        for (Map<String, String> failure : failures) {
            ObjectNode node = arr.addObject();
            failure.forEach(node::put);
        }
        return Response.ok(response).build();
    }

    private static Response insightRuleStateQuery(CloudWatchMetricsService service,
                                                  MultivaluedMap<String, String> params,
                                                  String region, String state) {
        List<Map<String, String>> failures = service.setInsightRulesState(
                memberList(params, "RuleNames.member."), state, region);
        XmlBuilder xml = new XmlBuilder().start("Failures");
        for (Map<String, String> failure : failures) {
            xml.start("member")
                    .elem("FailureResource", failure.get("FailureResource"))
                    .elem("ExceptionType", failure.get("ExceptionType"))
                    .elem("FailureCode", failure.get("FailureCode"))
                    .elem("FailureDescription", failure.get("FailureDescription"))
                    .end("member");
        }
        xml.end("Failures");
        String action = "ENABLED".equals(state) ? "EnableInsightRules" : "DisableInsightRules";
        return Response.ok(AwsQueryResponse.envelope(action, AwsNamespaces.CW, xml.build())).build();
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> values.add(n.asText()));
        }
        return values;
    }

    private static List<String> memberList(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(prefix + i);
            if (value == null) {
                break;
            }
            values.add(value);
        }
        return values;
    }

    private static List<Dimension> parseDimensions(JsonNode node) {
        List<Dimension> dims = new ArrayList<>();
        if (!node.isArray()) {
            return dims;
        }
        for (JsonNode d : node) {
            dims.add(new Dimension(d.path("Name").asText(), d.path("Value").asText()));
        }
        return dims;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.path(field).isNull()) {
            return null;
        }
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
