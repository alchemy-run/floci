package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CloudWatchAnomalyDetectorServiceTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchAnomalyDetectorService service;

    @BeforeEach
    void setUp() {
        service = new CloudWatchAnomalyDetectorService(
                new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void putDescribeDeleteRoundTrip() {
        ObjectNode put = MAPPER.createObjectNode();
        put.put("Namespace", "AWS/Lambda");
        put.put("MetricName", "Errors");
        put.put("Stat", "Sum");
        put.putArray("Dimensions").addObject()
                .put("Name", "FunctionName")
                .put("Value", "alchemy-test-anomaly-list");

        assertEquals(200, CloudWatchAnomalyDetectorActions.handleJson(service, MAPPER, "PutAnomalyDetector", put, REGION)
                .getStatus());

        ObjectNode describe = MAPPER.createObjectNode();
        describe.put("Namespace", "AWS/Lambda");
        describe.put("MetricName", "Errors");
        describe.putArray("AnomalyDetectorTypes").add("SINGLE_METRIC");
        Response described = CloudWatchAnomalyDetectorActions.handleJson(
                service, MAPPER, "DescribeAnomalyDetectors", describe, REGION);
        assertEquals(200, described.getStatus());
        ObjectNode body = (ObjectNode) described.getEntity();
        assertEquals(1, body.path("AnomalyDetectors").size());
        JsonNode detector = body.path("AnomalyDetectors").get(0);
        assertEquals("AWS/Lambda", detector.path("Namespace").asText());
        assertEquals("Errors", detector.path("MetricName").asText());
        assertEquals("Sum", detector.path("Stat").asText());
        assertEquals("AWS/Lambda", detector.path("SingleMetricAnomalyDetector").path("Namespace").asText());

        ObjectNode listed = (ObjectNode) CloudWatchAnomalyDetectorActions.handleJson(
                service, MAPPER, "DescribeAnomalyDetectors", MAPPER.createObjectNode(), REGION).getEntity();
        assertEquals(1, listed.path("AnomalyDetectors").size());

        assertEquals(200, CloudWatchAnomalyDetectorActions.handleJson(
                service, MAPPER, "DeleteAnomalyDetector", put, REGION).getStatus());
        ObjectNode after = (ObjectNode) CloudWatchAnomalyDetectorActions.handleJson(
                service, MAPPER, "DescribeAnomalyDetectors", MAPPER.createObjectNode(), REGION).getEntity();
        assertEquals(0, after.path("AnomalyDetectors").size());
    }

    @Test
    void deleteMissingThrowsResourceNotFound() {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("Namespace", "AWS/Lambda");
        req.put("MetricName", "Missing");
        req.put("Stat", "Sum");
        AwsException error = assertThrows(AwsException.class, () ->
                CloudWatchAnomalyDetectorActions.handleJson(service, MAPPER, "DeleteAnomalyDetector", req, REGION));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
        assertTrue(error.getMessage().contains("not found"));
    }
}
