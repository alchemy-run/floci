package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Handles CloudWatch Metrics requests via the JSON 1.0 protocol.
 * AWS SDK v3 for CloudWatch uses X-Amz-Target: GraniteServiceVersion20100801.*
 */
@ApplicationScoped
public class CloudWatchMetricsJsonHandler {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsJsonHandler.class);
    private final CloudWatchMetricsService metricsService;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudWatchMetricsJsonHandler(CloudWatchMetricsService metricsService, ObjectMapper objectMapper) {
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        String normalizedAction = action.substring(0, 1).toUpperCase() + action.substring(1);
        return switch (normalizedAction) {
            case "PutMetricData" -> handlePutMetricData(request, region);
            case "ListMetrics" -> handleListMetrics(request, region);
            case "GetMetricStatistics" -> handleGetMetricStatistics(request, region);
            case "PutMetricAlarm" -> handlePutMetricAlarm(request, region);
            case "PutCompositeAlarm" -> handlePutCompositeAlarm(request, region);
            case "DescribeAlarms" -> handleDescribeAlarms(request, region);
            case "DeleteAlarms" -> handleDeleteAlarms(request, region);
            case "SetAlarmState" -> handleSetAlarmState(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "GetMetricData" -> handleGetMetricData(request, region);
            case "DescribeInsightRules" -> handleDescribeInsightRules();
            case "PutAnomalyDetector", "DescribeAnomalyDetectors", "DeleteAnomalyDetector" ->
                    CloudWatchAnomalyDetectorActions.handleJson(objectMapper, normalizedAction, request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported by CloudWatch JSON."))
                    .build();
        };
    }

    private Response handlePutMetricData(JsonNode request, String region) {
        String namespace = request.path("Namespace").asText();
        LOG.infov("JSON PutMetricData raw: {0}", request);
        List<MetricDatum> datums = parseMetricDataJson(request.path("MetricData"));
        LOG.infov("JSON PutMetricData parsed {0} datums, sums={1}", datums.size(),
                datums.stream().map(d -> d.getMetricName() + "=" + d.getSum()).toList());
        metricsService.putMetricData(namespace, datums, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListMetrics(JsonNode request, String region) {
        String namespace = request.has("Namespace") ? request.path("Namespace").asText() : null;
        String metricName = request.has("MetricName") ? request.path("MetricName").asText() : null;
        List<Dimension> dimensions = parseDimensionsJson(request.path("Dimensions"));

        List<CloudWatchMetricsService.MetricIdentity> metrics =
                metricsService.listMetrics(namespace, metricName, dimensions, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode metricsArray = response.putArray("Metrics");
        for (var m : metrics) {
            ObjectNode mNode = metricsArray.addObject();
            mNode.put("Namespace", m.namespace());
            mNode.put("MetricName", m.metricName());
            ArrayNode dims = mNode.putArray("Dimensions");
            if (m.dimensions() != null) {
                for (Dimension d : m.dimensions()) {
                    dims.addObject().put("Name", d.name()).put("Value", d.value());
                }
            }
        }
        return Response.ok(response).build();
    }

    private Response handleGetMetricStatistics(JsonNode request, String region) {
        String namespace = request.path("Namespace").asText();
        String metricName = request.path("MetricName").asText();
        List<Dimension> dimensions = parseDimensionsJson(request.path("Dimensions"));
        int period = request.path("Period").asInt(60);
        Instant startTime = parseInstantNode(request.path("StartTime"));
        Instant endTime = parseInstantNode(request.path("EndTime"));

        List<String> statistics = new ArrayList<>();
        JsonNode statsNode = request.path("Statistics");
        if (statsNode.isArray()) {
            statsNode.forEach(s -> statistics.add(s.asText()));
        }

        List<CloudWatchMetricsService.Datapoint> datapoints =
                metricsService.getMetricStatistics(namespace, metricName, dimensions,
                        startTime, endTime, period, statistics, null, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Label", metricName);
        ArrayNode dps = response.putArray("Datapoints");
        for (var dp : datapoints) {
            ObjectNode dpNode = dps.addObject();
            dpNode.put("Timestamp", dp.timestamp().getEpochSecond());
            if (statistics.contains("Average")) dpNode.put("Average", dp.average());
            if (statistics.contains("Sum")) dpNode.put("Sum", dp.sum());
            if (statistics.contains("Minimum")) dpNode.put("Minimum", dp.minimum());
            if (statistics.contains("Maximum")) dpNode.put("Maximum", dp.maximum());
            if (statistics.contains("SampleCount")) dpNode.put("SampleCount", dp.sampleCount());
            dpNode.put("Unit", dp.unit());
        }
        return Response.ok(response).build();
    }

    private Response handlePutMetricAlarm(JsonNode request, String region) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(request.path("AlarmName").asText());
        alarm.setAlarmDescription(request.path("AlarmDescription").asText(null));
        alarm.setMetricName(request.path("MetricName").asText(null));
        alarm.setNamespace(request.path("Namespace").asText(null));
        alarm.setStatistic(request.path("Statistic").asText(null));
        alarm.setPeriod(request.path("Period").asInt(60));
        alarm.setUnit(request.path("Unit").asText(null));
        alarm.setEvaluationPeriods(request.path("EvaluationPeriods").asInt(1));
        alarm.setDatapointsToAlarm(request.path("DatapointsToAlarm").asInt(alarm.getEvaluationPeriods()));
        alarm.setThreshold(request.path("Threshold").asDouble(0));
        alarm.setComparisonOperator(request.path("ComparisonOperator").asText(null));
        alarm.setTreatMissingData(request.path("TreatMissingData").asText(null));
        alarm.setActionsEnabled(request.path("ActionsEnabled").asBoolean(true));
        alarm.setDimensions(parseDimensionsJson(request.path("Dimensions")));

        JsonNode alarmActions = request.path("AlarmActions");
        if (alarmActions.isArray()) {
            alarmActions.forEach(a -> alarm.getAlarmActions().add(a.asText()));
        }
        JsonNode okActions = request.path("OKActions");
        if (okActions.isArray()) {
            okActions.forEach(a -> alarm.getOkActions().add(a.asText()));
        }
        JsonNode insufficientDataActions = request.path("InsufficientDataActions");
        if (insufficientDataActions.isArray()) {
            insufficientDataActions.forEach(a -> alarm.getInsufficientDataActions().add(a.asText()));
        }

        JsonNode tagsNode = request.has("Tags") ? request.path("Tags") : request.path("tags");
        if (tagsNode.isArray()) {
            Map<String, String> tags = new LinkedHashMap<>();
            tagsNode.forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
            alarm.setTags(tags);
        }

        metricsService.putMetricAlarm(alarm, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutCompositeAlarm(JsonNode request, String region) {
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName(request.path("AlarmName").asText());
        alarm.setAlarmDescription(request.path("AlarmDescription").asText(null));
        alarm.setAlarmRule(request.path("AlarmRule").asText(null));
        alarm.setActionsEnabled(request.path("ActionsEnabled").asBoolean(true));
        alarm.setActionsSuppressor(request.path("ActionsSuppressor").asText(null));
        if (request.has("ActionsSuppressorWaitPeriod")) {
            alarm.setActionsSuppressorWaitPeriod(request.path("ActionsSuppressorWaitPeriod").asInt());
        }
        if (request.has("ActionsSuppressorExtensionPeriod")) {
            alarm.setActionsSuppressorExtensionPeriod(request.path("ActionsSuppressorExtensionPeriod").asInt());
        }

        JsonNode alarmActions = request.path("AlarmActions");
        if (alarmActions.isArray()) {
            alarmActions.forEach(a -> alarm.getAlarmActions().add(a.asText()));
        }
        JsonNode okActions = request.path("OKActions");
        if (okActions.isArray()) {
            okActions.forEach(a -> alarm.getOkActions().add(a.asText()));
        }
        JsonNode insufficientDataActions = request.path("InsufficientDataActions");
        if (insufficientDataActions.isArray()) {
            insufficientDataActions.forEach(a -> alarm.getInsufficientDataActions().add(a.asText()));
        }

        JsonNode tagsNode = request.has("Tags") ? request.path("Tags") : request.path("tags");
        if (tagsNode.isArray()) {
            Map<String, String> tags = new LinkedHashMap<>();
            tagsNode.forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
            alarm.setTags(tags);
        }

        metricsService.putCompositeAlarm(alarm, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeAlarms(JsonNode request, String region) {
        List<String> alarmNames = new ArrayList<>();
        JsonNode namesNode = request.path("AlarmNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> alarmNames.add(n.asText()));
        }
        String prefix = request.has("AlarmNamePrefix") ? request.path("AlarmNamePrefix").asText() : null;
        List<String> alarmTypes = new ArrayList<>();
        JsonNode typesNode = request.path("AlarmTypes");
        if (typesNode.isArray()) {
            typesNode.forEach(t -> alarmTypes.add(t.asText()));
        }
        boolean includeMetric = alarmTypes.isEmpty() || alarmTypes.contains("MetricAlarm");
        boolean includeComposite = alarmTypes.contains("CompositeAlarm");

        ObjectNode response = objectMapper.createObjectNode();
        if (includeMetric) {
            ArrayNode arr = response.putArray("MetricAlarms");
            for (MetricAlarm a : metricsService.describeAlarms(alarmNames, prefix, region)) {
                ObjectNode node = arr.addObject();
                node.put("AlarmName", a.getAlarmName());
                if (a.getAlarmArn() != null) node.put("AlarmArn", a.getAlarmArn());
                if (a.getAlarmDescription() != null) node.put("AlarmDescription", a.getAlarmDescription());
                ArrayNode alarmActions = node.putArray("AlarmActions");
                a.getAlarmActions().forEach(alarmActions::add);
                ArrayNode okActions = node.putArray("OKActions");
                a.getOkActions().forEach(okActions::add);
                ArrayNode insufficientDataActions = node.putArray("InsufficientDataActions");
                a.getInsufficientDataActions().forEach(insufficientDataActions::add);
                if (a.getMetricName() != null) node.put("MetricName", a.getMetricName());
                if (a.getNamespace() != null) node.put("Namespace", a.getNamespace());
                if (a.getStatistic() != null) node.put("Statistic", a.getStatistic());
                ArrayNode dimensions = node.putArray("Dimensions");
                a.getDimensions().forEach(d -> {
                    ObjectNode dimNode = dimensions.addObject();
                    dimNode.put("Name", d.name());
                    dimNode.put("Value", d.value());
                });
                node.put("Period", a.getPeriod());
                node.put("EvaluationPeriods", a.getEvaluationPeriods());
                node.put("Threshold", a.getThreshold());
                if (a.getComparisonOperator() != null) node.put("ComparisonOperator", a.getComparisonOperator());
                node.put("ActionsEnabled", a.isActionsEnabled());
                if (a.getStateValue() != null) node.put("StateValue", a.getStateValue());
                if (a.getStateReason() != null) node.put("StateReason", a.getStateReason());
                if (a.getStateReasonData() != null) node.put("StateReasonData", a.getStateReasonData());
                node.put("StateUpdatedTimestamp", a.getStateUpdatedTimestamp());
            }
        }
        if (includeComposite) {
            ArrayNode arr = response.putArray("CompositeAlarms");
            for (CompositeAlarm a : metricsService.describeCompositeAlarms(alarmNames, prefix, region)) {
                writeCompositeAlarm(arr.addObject(), a);
            }
        }
        return Response.ok(response).build();
    }

    private void writeCompositeAlarm(ObjectNode node, CompositeAlarm a) {
        node.put("AlarmName", a.getAlarmName());
        if (a.getAlarmArn() != null) node.put("AlarmArn", a.getAlarmArn());
        if (a.getAlarmDescription() != null) node.put("AlarmDescription", a.getAlarmDescription());
        if (a.getAlarmRule() != null) node.put("AlarmRule", a.getAlarmRule());
        node.put("ActionsEnabled", a.isActionsEnabled());
        ArrayNode alarmActions = node.putArray("AlarmActions");
        a.getAlarmActions().forEach(alarmActions::add);
        ArrayNode okActions = node.putArray("OKActions");
        a.getOkActions().forEach(okActions::add);
        ArrayNode insufficientDataActions = node.putArray("InsufficientDataActions");
        a.getInsufficientDataActions().forEach(insufficientDataActions::add);
        if (a.getStateValue() != null) node.put("StateValue", a.getStateValue());
        if (a.getStateReason() != null) node.put("StateReason", a.getStateReason());
        if (a.getStateReasonData() != null) node.put("StateReasonData", a.getStateReasonData());
        node.put("StateUpdatedTimestamp", a.getStateUpdatedTimestamp());
        node.put("AlarmConfigurationUpdatedTimestamp", a.getAlarmConfigurationUpdatedTimestamp());
        if (a.getActionsSuppressor() != null) node.put("ActionsSuppressor", a.getActionsSuppressor());
        if (a.getActionsSuppressorWaitPeriod() != null) {
            node.put("ActionsSuppressorWaitPeriod", a.getActionsSuppressorWaitPeriod());
        }
        if (a.getActionsSuppressorExtensionPeriod() != null) {
            node.put("ActionsSuppressorExtensionPeriod", a.getActionsSuppressorExtensionPeriod());
        }
    }

    private Response handleDeleteAlarms(JsonNode request, String region) {
        List<String> alarmNames = new ArrayList<>();
        JsonNode namesNode = request.path("AlarmNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> alarmNames.add(n.asText()));
        }
        metricsService.deleteAlarms(alarmNames, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetAlarmState(JsonNode request, String region) {
        String name = request.path("AlarmName").asText();
        String state = request.path("StateValue").asText();
        String reason = request.path("StateReason").asText(null);
        String reasonData = request.path("StateReasonData").asText(null);
        metricsService.setAlarmState(name, state, reason, reasonData, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String arn = request.has("ResourceARN") ? request.path("ResourceARN").asText() : request.path("ResourceArn").asText();
        if (arn.isEmpty()) arn = request.path("resourceArn").asText();

        Map<String, String> tags = metricsService.listTagsForResource(arn, region);
        ArrayNode tagsArray = objectMapper.createArrayNode();
        tags.forEach((k, v) -> tagsArray.addObject().put("Key", k).put("Value", v));
        return Response.ok(objectMapper.createObjectNode().set("Tags", tagsArray)).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String arn = request.has("ResourceARN") ? request.path("ResourceARN").asText() : request.path("ResourceArn").asText();
        if (arn.isEmpty()) arn = request.path("resourceArn").asText();

        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.has("Tags") ? request.path("Tags") : request.path("tags");
        if (tagsNode.isArray()) {
            tagsNode.forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
        }
        metricsService.tagResource(arn, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String arn = request.has("ResourceARN") ? request.path("ResourceARN").asText() : request.path("ResourceArn").asText();
        if (arn.isEmpty()) arn = request.path("resourceArn").asText();

        List<String> keys = new ArrayList<>();
        JsonNode keysNode = request.has("TagKeys") ? request.path("TagKeys") : request.path("tagKeys");
        if (keysNode.isArray()) {
            keysNode.forEach(k -> keys.add(k.asText()));
        }
        metricsService.untagResource(arn, keys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetMetricData(JsonNode request, String region) {
        Instant startTime = parseInstantNode(request.path("StartTime"));
        Instant endTime = parseInstantNode(request.path("EndTime"));

        List<CloudWatchMetricsService.MetricDataQuery> queries = new ArrayList<>();
        JsonNode queriesNode = request.path("MetricDataQueries");
        if (queriesNode.isArray()) {
            for (JsonNode qNode : queriesNode) {
                String id = qNode.path("Id").asText();
                String expression = qNode.has("Expression") ? qNode.path("Expression").asText() : null;
                String label = qNode.has("Label") ? qNode.path("Label").asText() : null;
                boolean returnData = qNode.path("ReturnData").asBoolean(true);

                CloudWatchMetricsService.MetricStat metricStat = null;
                JsonNode msNode = qNode.path("MetricStat");
                if (!msNode.isMissingNode()) {
                    JsonNode metricNode = msNode.path("Metric");
                    String namespace = metricNode.path("Namespace").asText();
                    String metricName = metricNode.path("MetricName").asText();
                    int period = msNode.path("Period").asInt(60);
                    String stat = msNode.path("Stat").asText();
                    String unit = msNode.has("Unit") ? msNode.path("Unit").asText() : null;
                    List<Dimension> dims = parseDimensionsJson(metricNode.path("Dimensions"));

                    metricStat = new CloudWatchMetricsService.MetricStat(
                            namespace, metricName, dims, period, stat, unit);
                }

                queries.add(new CloudWatchMetricsService.MetricDataQuery(
                        id, metricStat, expression, label, returnData));
            }
        }

        List<CloudWatchMetricsService.MetricDataResult> results =
                metricsService.getMetricData(queries, startTime, endTime, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode resultsArray = response.putArray("MetricDataResults");
        for (var r : results) {
            ObjectNode rNode = resultsArray.addObject();
            rNode.put("Id", r.id());
            rNode.put("Label", r.label());
            rNode.put("StatusCode", r.statusCode());

            ArrayNode tsArray = rNode.putArray("Timestamps");
            for (Instant ts : r.timestamps()) {
                tsArray.add(ts.getEpochSecond());
            }

            ArrayNode valArray = rNode.putArray("Values");
            for (Double v : r.values()) {
                valArray.add(v);
            }
        }
        return Response.ok(response).build();
    }

    private List<MetricDatum> parseMetricDataJson(JsonNode node) {
        List<MetricDatum> datums = new ArrayList<>();
        if (!node.isArray()) return datums;
        for (JsonNode item : node) {
            MetricDatum datum = new MetricDatum();
            datum.setMetricName(item.path("MetricName").asText());
            datum.setValue(item.path("Value").asDouble(0));
            datum.setUnit(item.path("Unit").asText(null));
            JsonNode ts = item.path("Timestamp");
            if (!ts.isMissingNode()) {
                Instant parsed = parseInstantNode(ts);
                if (parsed != null) datum.setTimestamp(parsed.getEpochSecond());
            }
            datum.setDimensions(parseDimensionsJson(item.path("Dimensions")));

            JsonNode statsValues = item.path("StatisticValues");
            if (!statsValues.isMissingNode()) {
                datum.setSampleCount(statsValues.path("SampleCount").asDouble(0));
                datum.setSum(statsValues.path("Sum").asDouble(0));
                datum.setMinimum(statsValues.path("Minimum").asDouble(0));
                datum.setMaximum(statsValues.path("Maximum").asDouble(0));
            }

            datums.add(datum);
        }
        return datums;
    }

    private List<Dimension> parseDimensionsJson(JsonNode node) {
        List<Dimension> dims = new ArrayList<>();
        if (!node.isArray()) return dims;
        for (JsonNode d : node) {
            dims.add(new Dimension(d.path("Name").asText(), d.path("Value").asText()));
        }
        return dims;
    }

    /**
     * Parse a JsonNode that may be a numeric epoch (long/double) or an ISO-8601 string.
     */
    private Instant parseInstantNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) {
            return Instant.ofEpochSecond(node.asLong());
        }
        return parseInstant(node.asText(null));
    }

    private Response handleDescribeInsightRules() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("InsightRules");
        return Response.ok(response).build();
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(value));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    private Response handleGetDashboard(JsonNode request, String region) {
        Dashboard dashboard = metricsService.getDashboard(textOrNull(request, "DashboardName"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DashboardName", dashboard.getName());
        if (dashboard.getArn() != null) {
            response.put("DashboardArn", dashboard.getArn());
        }
        if (dashboard.getBody() != null) {
            response.put("DashboardBody", dashboard.getBody());
        }
        return Response.ok(response).build();
    }

    private Response handlePutDashboard(JsonNode request, String region) {
        metricsService.putDashboard(
                textOrNull(request, "DashboardName"),
                textOrNull(request, "DashboardBody"),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("DashboardValidationMessages");
        return Response.ok(response).build();
    }

    private Response handleListDashboards(JsonNode request, String region) {
        List<Dashboard> dashboards = metricsService.listDashboards(
                textOrNull(request, "DashboardNamePrefix"), region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("DashboardEntries");
        for (Dashboard dashboard : dashboards) {
            ObjectNode entry = entries.addObject();
            entry.put("DashboardName", dashboard.getName());
            if (dashboard.getArn() != null) {
                entry.put("DashboardArn", dashboard.getArn());
            }
            entry.put("LastModified", dashboard.getLastModified());
            entry.put("Size", dashboard.getSize());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteDashboards(JsonNode request, String region) {
        List<String> names = new ArrayList<>();
        JsonNode namesNode = request.path("DashboardNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> names.add(n.asText()));
        }
        metricsService.deleteDashboards(names, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetMetricWidgetImage() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("MetricWidgetImage", TRANSPARENT_PNG);
        return Response.ok(response).build();
    }

    private Response handleDescribeAlarmsForMetric(JsonNode request, String region) {
        Integer period = request.has("Period") ? request.path("Period").asInt() : null;
        List<MetricAlarm> alarms = metricsService.describeAlarmsForMetric(
                textOrNull(request, "Namespace"),
                textOrNull(request, "MetricName"),
                textOrNull(request, "Statistic"),
                period,
                parseDimensionsJson(request.path("Dimensions")),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("MetricAlarms");
        for (MetricAlarm a : alarms) {
            ObjectNode node = arr.addObject();
            node.put("AlarmName", a.getAlarmName());
            if (a.getAlarmArn() != null) node.put("AlarmArn", a.getAlarmArn());
            if (a.getMetricName() != null) node.put("MetricName", a.getMetricName());
            if (a.getNamespace() != null) node.put("Namespace", a.getNamespace());
            if (a.getStatistic() != null) node.put("Statistic", a.getStatistic());
            node.put("Period", a.getPeriod());
            node.put("ActionsEnabled", a.isActionsEnabled());
            if (a.getStateValue() != null) node.put("StateValue", a.getStateValue());
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeAlarmHistory(JsonNode request, String region) {
        Integer maxRecords = request.has("MaxRecords") ? request.path("MaxRecords").asInt() : null;
        List<AlarmHistoryItem> items = metricsService.describeAlarmHistory(
                textOrNull(request, "AlarmName"), maxRecords, region);
        ObjectNode response = objectMapper.createObjectNode();
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
        return Response.ok(response).build();
    }

    private Response handleDescribeAlarmContributors() {
        throw new AwsException("ValidationException", "", 400);
    }

    private Response handleSetAlarmActions(JsonNode request, String region, boolean enabled) {
        List<String> names = new ArrayList<>();
        JsonNode namesNode = request.path("AlarmNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> names.add(n.asText()));
        }
        metricsService.setAlarmActionsEnabled(names, enabled, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetInsightRuleReport(JsonNode request, String region) {
        InsightRule rule = metricsService.requireInsightRule(textOrNull(request, "RuleName"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("KeyLabels");
        String aggregation = "Sum";
        if (rule.getDefinition() != null && rule.getDefinition().contains("\"AggregateOn\":\"Count\"")) {
            aggregation = "Count";
        }
        response.put("AggregationStatistic", aggregation);
        response.putArray("Contributors");
        response.putArray("MetricDatapoints");
        return Response.ok(response).build();
    }

    private Response handleSetInsightRulesState(JsonNode request, String region, String state) {
        List<String> names = new ArrayList<>();
        JsonNode namesNode = request.path("RuleNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> names.add(n.asText()));
        }
        List<Map<String, String>> failures = metricsService.setInsightRulesState(names, state, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Failures");
        for (Map<String, String> failure : failures) {
            ObjectNode node = arr.addObject();
            failure.forEach(node::put);
        }
        return Response.ok(response).build();
    }

    private Response handleListManagedInsightRules() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ManagedRules");
        return Response.ok(response).build();
    }

    private Response handleListMetricStreams() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Entries");
        return Response.ok(response).build();
    }
}
