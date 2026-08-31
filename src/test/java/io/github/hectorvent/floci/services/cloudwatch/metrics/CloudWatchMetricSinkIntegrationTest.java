package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * PutMetricData + GetMetricStatistics used by Alchemy
 * {@code test/AWS/CloudWatch/MetricSink.test.ts}: the sink packs &gt;1000
 * datums into sequential PutMetricData calls (1000 + remainder) and asserts
 * GetMetricStatistics Sum equals the published total for a unique dimension.
 */
class CloudWatchMetricSinkIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "Alchemy/MetricSinkTest";
    private static final String METRIC = "MetricSinkTestMetric";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchMetricsService service;
    private CloudWatchMetricsJsonHandler jsonHandler;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000")
        );
        jsonHandler = new CloudWatchMetricsJsonHandler(service, MAPPER);
    }

    @Test
    void jsonPutMetricDataSplitsAbove1000AndGetMetricStatisticsSumsAll() {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        assertEquals(200, putBatch(runId, 1000, now).getStatus());
        assertEquals(200, putBatch(runId, 500, now).getStatus());

        ObjectNode stats = getStats(runId, now.minusSeconds(300), now.plusSeconds(60));
        ArrayNode datapoints = (ArrayNode) stats.get("Datapoints");
        assertFalse(datapoints.isEmpty());
        double sum = 0;
        double samples = 0;
        for (var dp : datapoints) {
            sum += dp.path("Sum").asDouble();
            samples += dp.path("SampleCount").asDouble();
        }
        assertEquals(1500.0, sum, 0.001);
        assertEquals(1500.0, samples, 0.001);
    }

    @Test
    void jsonPutMetricDataValuesArrayAggregatesIntoStatisticSet() {
        String runId = "values-" + UUID.randomUUID();
        Instant now = Instant.now();
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", NAMESPACE);
        ObjectNode datum = req.putArray("MetricData").addObject();
        datum.put("MetricName", METRIC);
        datum.put("Unit", "Count");
        datum.putArray("Dimensions").addObject().put("Name", "Run").put("Value", runId);
        ArrayNode values = datum.putArray("Values");
        ArrayNode counts = datum.putArray("Counts");
        for (int i = 0; i < 1500; i++) {
            values.add(1);
            counts.add(1);
        }

        assertEquals(200, jsonHandler.handle("PutMetricData", req, REGION).getStatus());

        ObjectNode stats = getStats(runId, now.minusSeconds(300), now.plusSeconds(60));
        double sum = 0;
        for (var dp : stats.get("Datapoints")) {
            sum += dp.path("Sum").asDouble();
        }
        assertEquals(1500.0, sum, 0.001);
    }

    @Test
    void servicePutMetricData1500ScalarDatumsSumByDimension() {
        String runId = "svc-" + UUID.randomUUID();
        List<MetricDatum> first = scalarBatch(runId, 1000);
        List<MetricDatum> second = scalarBatch(runId, 500);
        service.putMetricData(NAMESPACE, first, REGION);
        service.putMetricData(NAMESPACE, second, REGION);

        Instant now = Instant.now();
        List<CloudWatchMetricsService.Datapoint> points = service.getMetricStatistics(
                NAMESPACE, METRIC, List.of(new Dimension("Run", runId)),
                now.minusSeconds(300), now.plusSeconds(60), 60,
                List.of("Sum", "SampleCount"), null, REGION);

        double sum = points.stream().mapToDouble(CloudWatchMetricsService.Datapoint::sum).sum();
        assertEquals(1500.0, sum, 0.001);
    }

    private List<MetricDatum> scalarBatch(String runId, int count) {
        List<MetricDatum> datums = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            MetricDatum d = new MetricDatum();
            d.setMetricName(METRIC);
            d.setValue(1);
            d.setUnit("Count");
            d.setDimensions(List.of(new Dimension("Run", runId)));
            datums.add(d);
        }
        return datums;
    }

    private Response putBatch(String runId, int count, Instant timestamp) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", NAMESPACE);
        ArrayNode data = req.putArray("MetricData");
        for (int i = 0; i < count; i++) {
            ObjectNode datum = data.addObject();
            datum.put("MetricName", METRIC);
            datum.put("Value", 1);
            datum.put("Unit", "Count");
            datum.put("Timestamp", timestamp.getEpochSecond());
            datum.putArray("Dimensions").addObject().put("Name", "Run").put("Value", runId);
        }
        return jsonHandler.handle("PutMetricData", req, REGION);
    }

    private ObjectNode getStats(String runId, Instant start, Instant end) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", NAMESPACE);
        req.put("MetricName", METRIC);
        req.put("Period", 60);
        req.put("StartTime", start.getEpochSecond());
        req.put("EndTime", end.getEpochSecond());
        req.putArray("Dimensions").addObject().put("Name", "Run").put("Value", runId);
        req.putArray("Statistics").add("Sum").add("SampleCount");
        Response resp = jsonHandler.handle("GetMetricStatistics", req, REGION);
        assertEquals(200, resp.getStatus());
        return (ObjectNode) resp.getEntity();
    }
}
