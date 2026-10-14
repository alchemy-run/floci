package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.logs.LogEventsIngested;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchMetadataHandlerTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudWatchMetricsJsonHandler json;
    private CloudWatchMetricsQueryHandler query;
    private CloudWatchMetadataService metadata;
    private CloudWatchInsightsService insights;
    private CloudWatchMetricsService metrics;
    private InMemoryStorage<String, ObjectNode> metadataStore;
    private AccountAwareStorageBackend<ObjectNode> insightStore;
    private RegionResolver regions;

    @BeforeEach
    void setUp() {
        regions = new RegionResolver(REGION, ACCOUNT);
        metrics = new CloudWatchMetricsService(new InMemoryStorage<>(), new InMemoryStorage<>(), regions);
        metadataStore = new InMemoryStorage<>();
        insightStore = AccountAwareStorageBackend.inMemory(ACCOUNT);
        metadata = new CloudWatchMetadataService(metadataStore, regions, mapper, metrics);
        insights = new CloudWatchInsightsService(insightStore, mapper, regions, metadata);
        var dashboards = new CloudWatchDashboardsService(new InMemoryStorage<>(), regions);
        var streams = new CloudWatchMetricStreamsService(new InMemoryStorage<>(), regions);
        json = new CloudWatchMetricsJsonHandler(metrics, dashboards, streams, mapper);
        json.metadataService = metadata;
        json.insightsService = insights;
        json.widgetRenderer = new MetricWidgetRenderer(mapper, metrics);
        query = new CloudWatchMetricsQueryHandler(metrics, dashboards, streams);
        query.jsonHandler = json;
    }

    @Test
    void compositeAlarmCrudUsesMetricStateAndPreservesTags() throws Exception {
        call("PutMetricAlarm", """
                {"AlarmName":"errors","Namespace":"App","MetricName":"Errors","Statistic":"Sum",
                 "Period":60,"EvaluationPeriods":1,"Threshold":10,"ComparisonOperator":"GreaterThanThreshold"}
                """);
        call("PutCompositeAlarm", """
                {"AlarmName":"aggregate","AlarmRule":"ALARM(\\"errors\\") AND NOT FALSE",
                 "Tags":[{"Key":"owner","Value":"test"}]}
                """);
        String arn = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:aggregate";
        call("SetAlarmState", """
                {"AlarmName":"errors","StateValue":"ALARM","StateReason":"test breach"}
                """);
        JsonNode described = call("DescribeAlarms", "{\"AlarmTypes\":[\"CompositeAlarm\"]}");
        assertEquals("ALARM", described.path("CompositeAlarms").get(0).path("StateValue").asText());
        assertTrue(described.path("MetricAlarms").isEmpty());
        call("PutCompositeAlarm", "{\"AlarmName\":\"aggregate\",\"AlarmRule\":\"FALSE\"}");
        assertEquals("test", call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .path("Tags").get(0).path("Value").asText());
        call("DisableAlarmActions", "{\"AlarmNames\":[\"aggregate\",\"errors\"]}");
        described = call("DescribeAlarms", "{\"AlarmTypes\":[\"CompositeAlarm\",\"MetricAlarm\"]}");
        assertFalse(described.path("CompositeAlarms").get(0).path("ActionsEnabled").asBoolean());
        assertFalse(described.path("MetricAlarms").get(0).path("ActionsEnabled").asBoolean());
        assertEquals("OK", described.path("CompositeAlarms").get(0).path("StateValue").asText());
        JsonNode page = call("DescribeAlarms", "{\"AlarmTypes\":[\"CompositeAlarm\",\"MetricAlarm\"],\"MaxRecords\":1}");
        assertEquals(1, page.path("CompositeAlarms").size());
        assertTrue(page.has("NextToken"));
        JsonNode next = call("DescribeAlarms", "{\"AlarmTypes\":[\"CompositeAlarm\",\"MetricAlarm\"],\"MaxRecords\":1,\"NextToken\":\""
                + page.path("NextToken").asText() + "\"}");
        assertEquals(1, next.path("MetricAlarms").size());
        assertFalse(next.has("NextToken"));
        call("DeleteAlarms", "{\"AlarmNames\":[\"aggregate\",\"errors\"]}");
        call("DeleteAlarms", "{\"AlarmNames\":[\"aggregate\",\"errors\"]}");
        described = call("DescribeAlarms", "{\"AlarmTypes\":[\"CompositeAlarm\",\"MetricAlarm\"]}");
        assertTrue(described.path("CompositeAlarms").isEmpty());
        assertTrue(described.path("MetricAlarms").isEmpty());
        JsonNode history = call("DescribeAlarmHistory", "{\"AlarmName\":\"errors\",\"HistoryItemType\":\"StateUpdate\"}");
        assertEquals("test breach", history.path("AlarmHistoryItems").get(0).path("HistorySummary").asText());
    }

    @Test
    void alarmBindingsFilterByMetricAndRejectUnsupportedContributors() throws Exception {
        query("PutMetricAlarm", "AlarmName", "latency", "Namespace", "App", "MetricName", "Latency",
                "Statistic", "Sum", "Period", "60", "EvaluationPeriods", "1", "Threshold", "5",
                "ComparisonOperator", "GreaterThanThreshold");
        assertTrue(query("DescribeAlarmsForMetric", "Namespace", "App", "MetricName", "Latency",
                "Statistic", "Sum", "Period", "60").contains("<AlarmName>latency</AlarmName>"));
        assertTrue(call("DescribeAlarmsForMetric", "{\"Namespace\":\"Other\",\"MetricName\":\"Latency\"}")
                .path("MetricAlarms").isEmpty());
        call("DisableAlarmActions", "{\"AlarmNames\":[\"latency\"]}");
        assertFalse(call("DescribeAlarms", "{\"AlarmNames\":[\"latency\"]}")
                .path("MetricAlarms").get(0).path("ActionsEnabled").asBoolean());
        query("EnableAlarmActions", "AlarmNames.member.1", "latency");
        assertTrue(call("DescribeAlarms", "{\"AlarmNames\":[\"latency\"]}")
                .path("MetricAlarms").get(0).path("ActionsEnabled").asBoolean());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> call("DescribeAlarmContributors", "{\"AlarmName\":\"latency\"}")).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> call("DescribeAlarmContributors", "{\"AlarmName\":\"missing\"}")).getErrorCode());
        assertEquals("InvalidParameterValueException", assertThrows(AwsException.class,
                () -> call("ListManagedInsightRules", "{\"ResourceARN\":\"arn:aws:cloudwatch:us-east-1:000000000000:alarm:latency\"}"))
                .getErrorCode());
    }

    @Test
    void queryAndJsonShareDetectorIdentityAndMetadata() throws Exception {
        query("PutAnomalyDetector", "Namespace", "App", "MetricName", "Latency", "Stat", "Average",
                "Dimensions.member.1.Name", "Service", "Dimensions.member.1.Value", "api",
                "Configuration.MetricTimezone", "UTC");
        JsonNode result = call("DescribeAnomalyDetectors", "{\"Namespace\":\"App\"}");
        assertEquals(1, result.path("AnomalyDetectors").size());
        assertEquals("UTC", result.path("AnomalyDetectors").get(0).path("Configuration").path("MetricTimezone").asText());
        call("PutAnomalyDetector", """
                {"SingleMetricAnomalyDetector":{"Namespace":"App","MetricName":"Latency","Stat":"Average",
                 "Dimensions":[{"Name":"Service","Value":"api"}]},"Configuration":{"MetricTimezone":"Europe/London"}}
                """);
        assertEquals(1, call("DescribeAnomalyDetectors", "{}").path("AnomalyDetectors").size());
        String xml = query("DescribeAnomalyDetectors", "Namespace", "App");
        assertTrue(xml.contains("<MetricTimezone>Europe/London</MetricTimezone>"));
        assertTrue(xml.contains("<SingleMetricAnomalyDetector>"));
        assertTrue(((JsonNode) json.handle("DescribeAnomalyDetectors", mapper.createObjectNode(), "eu-west-1")
                .getEntity()).path("AnomalyDetectors").isEmpty());
        query("DeleteAnomalyDetector", "Namespace", "App", "MetricName", "Latency", "Stat", "Average",
                "Dimensions.member.1.Name", "Service", "Dimensions.member.1.Value", "api");
        assertTrue(call("DescribeAnomalyDetectors", "{}").path("AnomalyDetectors").isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> query("DeleteAnomalyDetector",
                "Namespace", "App", "MetricName", "Latency", "Stat", "Average")).getErrorCode());
    }

    @Test
    void detectorConfigurationSurvivesServiceRecreationAndListsInPages() throws Exception {
        call("PutAnomalyDetector", "{\"Namespace\":\"App\",\"MetricName\":\"A\",\"Stat\":\"Sum\"}");
        call("PutAnomalyDetector", "{\"Namespace\":\"App\",\"MetricName\":\"B\",\"Stat\":\"Sum\"}");
        var restored = new CloudWatchMetadataService(metadataStore, regions, mapper, metrics);
        JsonNode page = restored.describeDetectors(mapper.readTree("{\"MaxResults\":1}"), REGION);
        assertEquals(1, page.path("AnomalyDetectors").size());
        assertTrue(page.has("NextToken"));
        JsonNode next = restored.describeDetectors(mapper.createObjectNode().put("MaxResults", 1)
                .put("NextToken", page.path("NextToken").asText()), REGION);
        assertEquals(1, next.path("AnomalyDetectors").size());
        assertFalse(next.has("NextToken"));
        assertNotEquals(page.path("AnomalyDetectors"), next.path("AnomalyDetectors"));
    }

    @Test
    void insightRulesReportActualFilteredLogContributors() throws Exception {
        putInsight("visitors", "Count");
        ingest("one", "{\"ip\":\"a\",\"status\":200}", 120);
        ingest("two", "{\"ip\":\"a\",\"status\":200}", 130);
        ingest("three", "{\"ip\":\"b\",\"status\":200}", 180);
        ingest("filtered", "{\"ip\":\"c\",\"status\":500}", 180);
        ingest("invalid", "not json", 180);
        ingest("one", "{\"ip\":\"a\",\"status\":200}", 120);
        ObjectNode report = report("visitors");
        assertEquals(3, report.path("AggregateValue").asInt());
        assertEquals(2, report.path("ApproximateUniqueCount").asInt());
        assertEquals("a", report.path("Contributors").get(0).path("Keys").get(0).asText());
        assertEquals(2, report.path("Contributors").get(0).path("ApproximateAggregateValue").asInt());
        assertEquals(2, report.path("Contributors").get(0).path("Datapoints").get(0).path("ApproximateValue").asInt());
        assertEquals(2, report.path("MetricDatapoints").size());
        query("DisableInsightRules", "RuleNames.member.1", "visitors");
        ingest("disabled", "{\"ip\":\"a\",\"status\":200}", 190);
        assertEquals(3, report("visitors").path("AggregateValue").asInt());
        query("EnableInsightRules", "RuleNames.member.1", "visitors");
        ingest("enabled", "{\"ip\":\"b\",\"status\":200}", 190);
        assertEquals(4, report("visitors").path("AggregateValue").asInt());
        assertTrue(query("DescribeInsightRules").contains("<Name>visitors</Name>"));
        assertTrue(query("GetInsightRuleReport", "RuleName", "visitors", "StartTime", "1970-01-01T00:01:40Z",
                "EndTime", "1970-01-01T00:05:00Z", "Period", "60").contains("<AggregateValue>4.0</AggregateValue>"));
        call("DeleteInsightRules", "{\"RuleNames\":[\"visitors\"]}");
        assertTrue(call("DescribeInsightRules", "{}").path("InsightRules").isEmpty());
        assertThrows(AwsException.class, () -> report("visitors"));
    }

    @Test
    void insightSumAndAccountIsolationUseStoredEvents() throws Exception {
        putInsight("bytes", "Sum");
        ingest("a", "{\"ip\":\"a\",\"status\":200,\"bytes\":7}", 120);
        ingest("b", "{\"ip\":\"a\",\"status\":200,\"bytes\":11}", 180);
        ingest("c", "{\"ip\":\"a\",\"status\":200}", 180);
        assertEquals(18, report("bytes").path("AggregateValue").asInt());
        assertTrue(insightStore.scanForAccount("111111111111", k -> true).isEmpty());
        LogEvent other = event("other", "{\"ip\":\"foreign\",\"status\":200,\"bytes\":100}", 190);
        insights.ingest(new LogEventsIngested("111111111111", REGION, "/app/access", "stream", List.of(other)));
        assertEquals(18, report("bytes").path("AggregateValue").asInt());
        assertTrue(insightStore.scanForAccount("111111111111", k -> true).isEmpty());
        String arn = "arn:aws:cloudwatch:us-east-1:000000000000:insight-rule/bytes";
        call("TagResource", "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"owner\",\"Value\":\"test\"}]}");
        putInsight("bytes", "Sum");
        assertEquals("owner", call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}").path("Tags").get(0).path("Key").asText());
        query("UntagResource", "ResourceARN", arn, "TagKeys.member.1", "owner");
        assertTrue(call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}").path("Tags").isEmpty());
    }

    @Test
    void muteRulesPersistAndReflectTheirActualSchedule() throws Exception {
        Instant start = Instant.now().minusSeconds(60);
        ObjectNode request = mapper.createObjectNode().put("Name", "maintenance");
        request.putObject("Rule").putObject("Schedule").put("Expression", "at(" + start + ")").put("Duration", "PT1H");
        request.putObject("MuteTargets").putArray("AlarmNames").add("errors");
        json.handle("PutAlarmMuteRule", request, REGION);
        JsonNode rule = call("GetAlarmMuteRule", "{\"AlarmMuteRuleName\":\"maintenance\"}");
        assertEquals("ACTIVE", rule.path("Status").asText());
        assertTrue(metadata.muted("errors", REGION));
        assertFalse(metadata.muted("other", REGION));
        assertFalse(metadata.muted("errors", "eu-west-1"));
        assertEquals(1, call("ListAlarmMuteRules", "{\"AlarmName\":\"errors\",\"Statuses\":[\"ACTIVE\"]}")
                .path("AlarmMuteRuleSummaries").size());
        assertTrue(query("GetAlarmMuteRule", "AlarmMuteRuleName", "maintenance").contains("<Status>ACTIVE</Status>"));
        request.put("ExpireDate", Instant.now().minusSeconds(1).getEpochSecond());
        json.handle("PutAlarmMuteRule", request, REGION);
        assertFalse(metadata.muted("errors", REGION));
        assertEquals("EXPIRED", call("GetAlarmMuteRule", "{\"AlarmMuteRuleName\":\"maintenance\"}").path("Status").asText());
        query("DeleteAlarmMuteRule", "AlarmMuteRuleName", "maintenance");
        assertTrue(call("ListAlarmMuteRules", "{}").path("AlarmMuteRuleSummaries").isEmpty());
    }

    @Test
    void widgetPngPixelsComeFromRequestedMetricData() throws Exception {
        String widget = """
                {"metrics":[["App","Count"]],"width":300,"height":200,"period":60,
                 "start":"2025-01-01T00:00:00Z","end":"2025-01-01T00:10:00Z"}
                """;
        ObjectNode request = mapper.createObjectNode().put("MetricWidget", widget);
        byte[] empty = ((JsonNode) json.handle("GetMetricWidgetImage", request, REGION).getEntity()).path("MetricWidgetImage").binaryValue();
        query("PutMetricData", "Namespace", "App", "MetricData.member.1.MetricName", "Count",
                "MetricData.member.1.Timestamp", "2025-01-01T00:03:00Z", "MetricData.member.1.Value", "5");
        byte[] populated = ((JsonNode) json.handle("GetMetricWidgetImage", request, REGION).getEntity()).path("MetricWidgetImage").binaryValue();
        assertEquals(0x89504e470d0a1a0aL, new DataInputStream(new ByteArrayInputStream(populated)).readLong());
        assertFalse(Arrays.equals(pixels(empty), pixels(populated)));
        query("PutMetricData", "Namespace", "Other", "MetricData.member.1.MetricName", "Count",
                "MetricData.member.1.Timestamp", "2025-01-01T00:04:00Z", "MetricData.member.1.Value", "100");
        byte[] unchanged = ((JsonNode) json.handle("GetMetricWidgetImage", request, REGION).getEntity()).path("MetricWidgetImage").binaryValue();
        assertArrayEquals(pixels(populated), pixels(unchanged));
        assertTrue(query("GetMetricWidgetImage", "MetricWidget", widget).contains("<MetricWidgetImage>iVBOR"));
    }

    @Test
    void valuesAndCountsAggregateWithTheRequestedUnit() throws Exception {
        call("PutMetricData", """
                {"Namespace":"App","MetricData":[{"MetricName":"Latency","Values":[2,8],"Counts":[3,1],
                 "Unit":"Milliseconds","Timestamp":120}]}
                """);
        JsonNode result = call("GetMetricStatistics", """
                {"Namespace":"App","MetricName":"Latency","Period":60,"StartTime":100,"EndTime":200,
                 "Unit":"Milliseconds","Statistics":["Sum","Average","SampleCount"]}
                """);
        assertEquals(14, result.path("Datapoints").get(0).path("Sum").asDouble());
        assertEquals(3.5, result.path("Datapoints").get(0).path("Average").asDouble());
        assertEquals(4, result.path("Datapoints").get(0).path("SampleCount").asDouble());
        assertTrue(call("GetMetricStatistics", """
                {"Namespace":"App","MetricName":"Latency","Period":60,"StartTime":100,"EndTime":200,
                 "Unit":"Seconds","Statistics":["Sum"]}
                """).path("Datapoints").isEmpty());
    }

    private ObjectNode call(String action, String body) throws Exception {
        return (ObjectNode) json.handle(action, mapper.readTree(body), REGION).getEntity();
    }

    private String query(String action, String... fields) {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) params.putSingle(fields[i], fields[i + 1]);
        return (String) query.handle(action, params, REGION).getEntity();
    }

    private void putInsight(String name, String aggregate) {
        ObjectNode definition = mapper.createObjectNode();
        definition.putObject("Schema").put("Name", "CloudWatchLogRule").put("Version", 1);
        definition.putArray("LogGroupNames").add("/app/*");
        definition.put("LogFormat", "JSON").put("AggregateOn", aggregate);
        ObjectNode contribution = definition.putObject("Contribution");
        contribution.putArray("Keys").add("$.ip");
        contribution.put("ValueOf", "$.bytes");
        contribution.putArray("Filters").addObject().put("Match", "$.status").put("LessThan", 400);
        ObjectNode request = mapper.createObjectNode().put("RuleName", name).put("RuleDefinition", definition.toString());
        json.handle("PutInsightRule", request, REGION);
    }

    private void ingest(String id, String message, long seconds) {
        insights.ingest(new LogEventsIngested(ACCOUNT, REGION, "/app/access", "stream", List.of(event(id, message, seconds))));
    }

    private LogEvent event(String id, String message, long seconds) {
        LogEvent event = new LogEvent();
        event.setEventId(id);
        event.setMessage(message);
        event.setTimestamp(seconds * 1000);
        return event;
    }

    private ObjectNode report(String name) {
        ObjectNode request = mapper.createObjectNode().put("RuleName", name).put("StartTime", 100).put("EndTime", 300).put("Period", 60);
        request.putArray("Metrics").add("Sum").add("UniqueContributors");
        return insights.report(request, REGION);
    }

    private byte[] pixels(byte[] png) throws Exception {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(png));
        input.readLong();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        while (input.available() > 0) {
            int length = input.readInt();
            String type = new String(input.readNBytes(4), StandardCharsets.US_ASCII);
            byte[] chunk = input.readNBytes(length);
            input.readInt();
            if (type.equals("IDAT")) compressed.write(chunk);
        }
        return new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray())).readAllBytes();
    }
}
