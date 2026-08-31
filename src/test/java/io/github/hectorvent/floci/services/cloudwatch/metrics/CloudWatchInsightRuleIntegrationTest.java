package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contributor Insights rule operations used by Alchemy
 * {@code test/AWS/CloudWatch/InsightRule.test.ts}: put, describe, tag, list, delete.
 */
class CloudWatchInsightRuleIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFINITION = """
            {"Schema":{"Name":"CloudWatchLogRule","Version":1},"LogGroupNames":["alchemy-test-insight-rule-list-log-group"],"LogFormat":"JSON","Contribution":{"Keys":["$.ip"],"Filters":[]},"AggregateOn":"Count"}""";

    private CloudWatchMetricsService service;
    private CloudWatchMetricsJsonHandler jsonHandler;
    private CloudWatchMetricsQueryHandler queryHandler;

    @BeforeEach
    void setUp() {
        service = new CloudWatchMetricsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT)
        );
        jsonHandler = new CloudWatchMetricsJsonHandler(service, MAPPER);
        queryHandler = new CloudWatchMetricsQueryHandler(service);
    }

    @Test
    void jsonPutDescribeTagAndDeleteRoundTrip() {
        String name = "alchemy-test-insight-rule-list";
        ObjectNode put = MAPPER.createObjectNode();
        put.put("RuleName", name);
        put.put("RuleState", "ENABLED");
        put.put("RuleDefinition", DEFINITION);

        Response putResp = jsonHandler.handle("PutInsightRule", put, REGION);
        assertEquals(200, putResp.getStatus());

        ObjectNode describe = MAPPER.createObjectNode();
        Response describeResp = jsonHandler.handle("DescribeInsightRules", describe, REGION);
        assertEquals(200, describeResp.getStatus());
        ObjectNode body = (ObjectNode) describeResp.getEntity();
        assertEquals(1, body.path("InsightRules").size());
        assertEquals(name, body.path("InsightRules").get(0).path("Name").asText());
        assertEquals("ENABLED", body.path("InsightRules").get(0).path("State").asText());
        assertTrue(body.path("InsightRules").get(0).path("Schema").asText().contains("CloudWatchLogRule"));
        assertEquals(DEFINITION, body.path("InsightRules").get(0).path("Definition").asText());
        assertFalse(body.path("InsightRules").get(0).path("ManagedRule").asBoolean());

        String arn = "arn:aws:cloudwatch:" + REGION + ":" + ACCOUNT + ":insight-rule/" + name;
        ObjectNode tag = MAPPER.createObjectNode();
        tag.put("ResourceARN", arn);
        tag.putArray("Tags").addObject().put("Key", "alchemy::id").put("Value", "ListInsightRule");
        assertEquals(200, jsonHandler.handle("TagResource", tag, REGION).getStatus());

        ObjectNode listTags = MAPPER.createObjectNode().put("ResourceARN", arn);
        Response tagsResp = jsonHandler.handle("ListTagsForResource", listTags, REGION);
        assertEquals(200, tagsResp.getStatus());
        ObjectNode tagsBody = (ObjectNode) tagsResp.getEntity();
        assertEquals(1, tagsBody.path("Tags").size());
        assertEquals("alchemy::id", tagsBody.path("Tags").get(0).path("Key").asText());
        assertEquals("ListInsightRule", tagsBody.path("Tags").get(0).path("Value").asText());

        ObjectNode delete = MAPPER.createObjectNode();
        delete.putArray("RuleNames").add(name);
        Response deleteResp = jsonHandler.handle("DeleteInsightRules", delete, REGION);
        assertEquals(200, deleteResp.getStatus());
        assertEquals(0, ((ObjectNode) deleteResp.getEntity()).path("Failures").size());

        Response after = jsonHandler.handle("DescribeInsightRules", MAPPER.createObjectNode(), REGION);
        assertEquals(0, ((ObjectNode) after.getEntity()).path("InsightRules").size());
    }

    @Test
    void queryPutAndDescribeStayAlignedWithJson() {
        String name = "query-insight-rule";
        MultivaluedMap<String, String> put = new MultivaluedHashMap<>();
        put.add("RuleName", name);
        put.add("RuleState", "DISABLED");
        put.add("RuleDefinition", DEFINITION);
        Response putResp = queryHandler.handle("PutInsightRule", put, REGION);
        assertEquals(200, putResp.getStatus());

        Response describeResp = queryHandler.handle("DescribeInsightRules", new MultivaluedHashMap<>(), REGION);
        assertEquals(200, describeResp.getStatus());
        String xml = (String) describeResp.getEntity();
        assertTrue(xml.contains("<Name>" + name + "</Name>"));
        assertTrue(xml.contains("<State>DISABLED</State>"));
        assertTrue(xml.contains("CloudWatchLogRule"));

        MultivaluedMap<String, String> delete = new MultivaluedHashMap<>();
        delete.add("RuleNames.member.1", name);
        Response deleteResp = queryHandler.handle("DeleteInsightRules", delete, REGION);
        assertEquals(200, deleteResp.getStatus());
        String deleteXml = (String) deleteResp.getEntity();
        assertTrue(deleteXml.contains("<Failures>"));
    }

    @Test
    void putInsightRuleWithoutNameIsAClientError() {
        ObjectNode put = MAPPER.createObjectNode();
        put.put("RuleDefinition", DEFINITION);
        try {
            jsonHandler.handle("PutInsightRule", put, REGION);
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            assertEquals("MissingRequiredParameterException", e.getErrorCode());
            return;
        }
        throw new AssertionError("expected MissingRequiredParameterException");
    }
}
