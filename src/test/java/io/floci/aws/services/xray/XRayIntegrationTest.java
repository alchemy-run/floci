package io.floci.aws.services.xray;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * X-Ray: segment ingestion and validation, trace summaries filtered by filter expressions, batch reads with
 * separately sent subsegments merged in, service and trace graphs, time series, groups, sampling rules and targets,
 * encryption, resource policies, indexing rules, Transaction Search with trace retrieval, insights announced to
 * EventBridge, and tags.
 */
@QuarkusTest
class XRayIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification xray() {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/xray/aws4_request")
                .contentType("application/json");
    }

    private static ValidatableResponse post(String path, String body) {
        return xray().body(body).post(path).then();
    }

    static String traceId() {
        return "1-" + Long.toHexString(System.currentTimeMillis() / 1000) + "-" + hex(24);
    }

    static String hex(int length) {
        byte[] bytes = new byte[length / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static String quote(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void put(String... documents) {
        List<String> quoted = new ArrayList<>();
        for (String document : documents) {
            quoted.add(quote(document));
        }
        post("/TraceSegments", "{\"TraceSegmentDocuments\":[" + String.join(",", quoted) + "]}").statusCode(200)
                .body("UnprocessedTraceSegments", hasSize(0));
    }

    /** An api segment that calls DynamoDB and the orders service; orders faults when asked to. */
    private static String[] requestTrace(String traceId, boolean fault, String user) {
        double start = now() - 1;
        String api = hex(16);
        String call = hex(16);
        String apiSegment = "{\"name\":\"api\",\"id\":\"" + api + "\",\"trace_id\":\"" + traceId + "\",\"start_time\":"
                + start + ",\"end_time\":" + (start + 0.3) + ",\"origin\":\"AWS::EC2::Instance\",\"user\":\"" + user
                + "\",\"annotations\":{\"tier\":\"gold\",\"items\":3},\"http\":{\"request\":{\"method\":\"POST\","
                + "\"url\":\"https://api.example.com/checkout\"},\"response\":{\"status\":" + (fault ? 502 : 200)
                + "}},\"fault\":" + fault + ",\"subsegments\":[{\"name\":\"DynamoDB\",\"id\":\"" + hex(16)
                + "\",\"start_time\":" + (start + 0.01) + ",\"end_time\":" + (start + 0.05) + ",\"namespace\":\"aws\"}]}";
        String callSubsegment = "{\"type\":\"subsegment\",\"name\":\"orders\",\"id\":\"" + call + "\",\"trace_id\":\""
                + traceId + "\",\"parent_id\":\"" + api + "\",\"start_time\":" + (start + 0.1) + ",\"end_time\":"
                + (start + 0.25) + ",\"namespace\":\"remote\",\"fault\":" + fault + "}";
        String orders = "{\"name\":\"orders\",\"id\":\"" + hex(16) + "\",\"trace_id\":\"" + traceId + "\",\"parent_id\":\""
                + call + "\",\"start_time\":" + (start + 0.11) + ",\"end_time\":" + (start + 0.24)
                + ",\"origin\":\"AWS::ECS::Container\",\"fault\":" + fault + (fault
                ? ",\"cause\":{\"exceptions\":[{\"type\":\"OrderStoreDown\",\"message\":\"store unavailable\"}]}" : "")
                + "}";
        return new String[]{apiSegment, callSubsegment, orders};
    }

    @Test
    void segmentsSummariesGraphsAndTimeSeries() {
        String good = traceId();
        String bad = traceId();
        put(requestTrace(good, false, "ann"));
        put(requestTrace(bad, true, "bob"));
        post("/TraceSegments", "{\"TraceSegmentDocuments\":[\"not json\",\"{\\\"name\\\":\\\"x\\\",\\\"id\\\":\\\""
                + hex(16) + "\\\",\\\"trace_id\\\":\\\"1-00000001-" + hex(24) + "\\\",\\\"start_time\\\":1,"
                + "\\\"end_time\\\":2}\"]}").statusCode(200)
                .body("UnprocessedTraceSegments.ErrorCode", equalTo(List.of("InvalidSegment", "InvalidTraceId")));

        double start = now() - 600;
        double end = now() + 60;
        String window = "\"StartTime\":" + start + ",\"EndTime\":" + end;
        List<String> all = post("/TraceSummaries", "{" + window + "}").statusCode(200).extract()
                .path("TraceSummaries.Id");
        assertTrue(all.containsAll(List.of(good, bad)), all.toString());
        assertEquals(List.of(bad), summaries(window, "fault AND service(\"orders\")"));
        assertEquals(List.of(good), summaries(window, "user = \"ann\" AND annotation.tier = \"gold\""));
        assertEquals(List.of(bad), summaries(window, "http.status >= 500 AND http.url CONTAINS \"checkout\""));
        assertEquals(List.of(bad), summaries(window, "edge(\"api\", \"orders\") { fault }"));
        assertEquals(List.of(good), summaries(window, "service(id(name: \"orders\", type: \"AWS::ECS::Container\")) "
                + "{ ok } AND annotation[items] = 3"));
        post("/TraceSummaries", "{" + window + ",\"FilterExpression\":\"bogus = 1\"}").statusCode(400)
                .body("__type", containsString("InvalidRequestException"));
        post("/TraceSummaries", "{\"StartTime\":" + (now() - 2 * 86400) + ",\"EndTime\":" + now() + "}")
                .statusCode(400);
        ValidatableResponse summary = post("/TraceSummaries", "{" + window + ",\"FilterExpression\":\"fault\"}");
        summary.body("TraceSummaries[0].HasFault", equalTo(true))
                .body("TraceSummaries[0].EntryPoint.Name", equalTo("api"))
                .body("TraceSummaries[0].Http.HttpStatus", equalTo(502))
                .body("TraceSummaries[0].FaultRootCauses.Services.flatten().Name", hasItem("orders"))
                .body("TraceSummaries[0].FaultRootCauses.Services.flatten().EntityPath.flatten().Exceptions.flatten()"
                        + ".Name", hasItem("OrderStoreDown"));

        // A batch read merges subsegments sent on their own into their parents.
        post("/Traces", "{\"TraceIds\":[\"" + good + "\",\"1-" + Long.toHexString((long) now()) + "-" + hex(24)
                + "\"]}").statusCode(200).body("Traces", hasSize(1)).body("Traces[0].Segments", hasSize(2))
                .body("UnprocessedTraceIds", hasSize(1))
                .body("Traces[0].Segments.find { it.Document.contains('\"api\"') }.Document",
                        containsString("\"orders\""));

        // The service graph: api calls DynamoDB (inferred) and orders, which faulted once.
        ValidatableResponse graph = post("/ServiceGraph", "{" + window + "}").statusCode(200);
        List<String> names = graph.extract().path("Services.Name");
        assertTrue(names.containsAll(List.of("api", "orders", "DynamoDB")), names.toString());
        graph.body("Services.find { it.Name == 'api' }.Root", equalTo(true))
                .body("Services.find { it.Name == 'orders' }.SummaryStatistics.FaultStatistics.TotalCount",
                        equalTo(1))
                .body("Services.find { it.Name == 'api' }.Edges", hasSize(2));
        post("/TraceGraph", "{\"TraceIds\":[\"" + bad + "\"]}").statusCode(200).body("Services", hasSize(3));
        post("/TimeSeriesServiceStatistics", "{" + window + ",\"EntitySelectorExpression\":\"service(\\\"orders\\\")\","
                + "\"Period\":60}").statusCode(200)
                .body("TimeSeriesServiceStatistics.ServiceSummaryStatistics.TotalCount.sum()", equalTo(2));
        post("/TimeSeriesServiceStatistics", "{" + window + ",\"Period\":30}").statusCode(400);
    }

    private static List<String> summaries(String window, String filter) {
        return post("/TraceSummaries", "{" + window + ",\"FilterExpression\":" + quote(filter) + "}").statusCode(200)
                .extract().path("TraceSummaries.Id");
    }

    @Test
    void groupsSamplingAndTags() {
        post("/CreateGroup", "{\"GroupName\":\"broken\",\"FilterExpression\":\"nonsense(\"}").statusCode(400);
        String arn = post("/CreateGroup", "{\"GroupName\":\"checkout\",\"FilterExpression\":\"service(\\\"api\\\")\","
                + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"web\"}]}").statusCode(200)
                .body("Group.InsightsConfiguration.InsightsEnabled", equalTo(false)).extract().path("Group.GroupARN");
        post("/CreateGroup", "{\"GroupName\":\"checkout\"}").statusCode(400);
        post("/Groups", "{}").statusCode(200).body("Groups[0].GroupName", equalTo("Default"))
                .body("Groups.GroupName", hasItem("checkout"));
        post("/UpdateGroup", "{\"GroupARN\":\"" + arn + "\",\"InsightsConfiguration\":{\"NotificationsEnabled\":true}}")
                .statusCode(400);
        post("/UpdateGroup", "{\"GroupName\":\"checkout\",\"InsightsConfiguration\":{\"InsightsEnabled\":true}}")
                .statusCode(200).body("Group.InsightsConfiguration.InsightsEnabled", equalTo(true));
        post("/ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}").statusCode(200)
                .body("Tags[0].Key", equalTo("team"));
        post("/DeleteGroup", "{\"GroupName\":\"Default\"}").statusCode(400);

        post("/GetSamplingRules", "{}").statusCode(200)
                .body("SamplingRuleRecords.SamplingRule.RuleName", hasItem("Default"))
                .body("SamplingRuleRecords.find { it.SamplingRule.RuleName == 'Default' }.SamplingRule.FixedRate",
                        equalTo(0.05f));
        String rule = "{\"SamplingRule\":{\"RuleName\":\"checkout\",\"ResourceARN\":\"*\",\"Priority\":10,"
                + "\"FixedRate\":0.5,\"ReservoirSize\":4,\"ServiceName\":\"api\",\"ServiceType\":\"*\",\"Host\":\"*\","
                + "\"HTTPMethod\":\"POST\",\"URLPath\":\"/checkout*\",\"Version\":1}}";
        String ruleArn = post("/CreateSamplingRule", rule).statusCode(200).extract()
                .path("SamplingRuleRecord.SamplingRule.RuleARN");
        post("/CreateSamplingRule", rule).statusCode(400);
        double ts = now();
        post("/SamplingTargets", "{\"SamplingStatisticsDocuments\":[{\"RuleName\":\"checkout\",\"ClientID\":\""
                + hex(24) + "\",\"Timestamp\":" + ts + ",\"RequestCount\":10,\"SampledCount\":4,\"BorrowCount\":1},"
                + "{\"RuleName\":\"checkout\",\"ClientID\":\"" + hex(24) + "\",\"Timestamp\":" + ts
                + ",\"RequestCount\":6,\"SampledCount\":2,\"BorrowCount\":0},{\"RuleName\":\"missing\",\"ClientID\":\""
                + hex(24) + "\",\"Timestamp\":" + ts + ",\"RequestCount\":1,\"SampledCount\":0}]}").statusCode(200)
                .body("SamplingTargetDocuments", hasSize(2))
                .body("SamplingTargetDocuments.ReservoirQuota.sum()", equalTo(4))
                .body("SamplingTargetDocuments.FixedRate", everyItem(equalTo(0.5f)))
                .body("UnprocessedStatistics[0].RuleName", equalTo("missing"));
        post("/SamplingStatisticSummaries", "{}").statusCode(200)
                .body("SamplingStatisticSummaries.find { it.RuleName == 'checkout' }.RequestCount", equalTo(16))
                .body("SamplingStatisticSummaries.find { it.RuleName == 'checkout' }.SampledCount", equalTo(6));
        post("/UpdateSamplingRule", "{\"SamplingRuleUpdate\":{\"RuleName\":\"Default\",\"ServiceName\":\"x\"}}")
                .statusCode(400);
        post("/UpdateSamplingRule", "{\"SamplingRuleUpdate\":{\"RuleARN\":\"" + ruleArn + "\",\"FixedRate\":0.2}}")
                .statusCode(200).body("SamplingRuleRecord.SamplingRule.FixedRate", equalTo(0.2f));
        post("/TagResource", "{\"ResourceARN\":\"" + ruleArn + "\",\"Tags\":[{\"Key\":\"a\",\"Value\":\"b\"}]}")
                .statusCode(200);
        post("/UntagResource", "{\"ResourceARN\":\"" + ruleArn + "\",\"TagKeys\":[\"a\"]}").statusCode(200);
        post("/ListTagsForResource", "{\"ResourceARN\":\"" + ruleArn + "\"}").body("Tags", hasSize(0));
        post("/DeleteSamplingRule", "{\"RuleName\":\"Default\"}").statusCode(400);
        post("/DeleteSamplingRule", "{\"RuleName\":\"checkout\"}").statusCode(200);
        post("/ListTagsForResource", "{\"ResourceARN\":\"" + ruleArn + "\"}").statusCode(404);
        post("/DeleteGroup", "{\"GroupName\":\"checkout\"}").statusCode(200);
        post("/GetGroup", "{\"GroupName\":\"checkout\"}").statusCode(400);
    }

    @Test
    void encryptionPoliciesAndIndexing() {
        post("/EncryptionConfig", "{}").statusCode(200).body("EncryptionConfig.Type", equalTo("NONE"));
        post("/PutEncryptionConfig", "{\"Type\":\"KMS\",\"KeyId\":\"alias/missing\"}").statusCode(400);
        String keyId = given().header("X-Amz-Target", "TrentService.CreateKey").contentType("application/x-amz-json-1.1")
                .body("{}").post("/").then().statusCode(200).extract().path("KeyMetadata.KeyId");
        post("/PutEncryptionConfig", "{\"Type\":\"KMS\",\"KeyId\":\"" + keyId + "\"}").statusCode(200)
                .body("EncryptionConfig.Status", equalTo("UPDATING"))
                .body("EncryptionConfig.KeyId", containsString(keyId));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                post("/EncryptionConfig", "{}").body("EncryptionConfig.Status", equalTo("ACTIVE")));
        post("/PutEncryptionConfig", "{\"Type\":\"NONE\"}").statusCode(200);

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":"
                + "\"sns.amazonaws.com\"},\"Action\":[\"xray:PutTraceSegments\"],\"Resource\":\"*\"}]}";
        post("/PutResourcePolicy", "{\"PolicyName\":\"sns\",\"PolicyDocument\":" + quote(policy.replace("xray:",
                "s3:")) + "}").statusCode(400).body("__type", containsString("MalformedPolicyDocumentException"));
        String revision = post("/PutResourcePolicy", "{\"PolicyName\":\"sns\",\"PolicyDocument\":" + quote(policy)
                + "}").statusCode(200).extract().path("ResourcePolicy.PolicyRevisionId");
        post("/PutResourcePolicy", "{\"PolicyName\":\"sns\",\"PolicyDocument\":" + quote(policy)
                + ",\"PolicyRevisionId\":\"stale\"}").statusCode(400)
                .body("__type", containsString("InvalidPolicyRevisionIdException"));
        post("/ListResourcePolicies", "{}").statusCode(200).body("ResourcePolicies.PolicyName", hasItem("sns"));
        post("/DeleteResourcePolicy", "{\"PolicyName\":\"sns\",\"PolicyRevisionId\":\"" + revision + "\"}")
                .statusCode(200);
        post("/ListResourcePolicies", "{}").body("ResourcePolicies", hasSize(0));

        post("/GetIndexingRules", "{}").statusCode(200).body("IndexingRules[0].Name", equalTo("Default"));
        post("/UpdateIndexingRule", "{\"Name\":\"Default\",\"Rule\":{\"Probabilistic\":{\"DesiredSamplingPercentage\":"
                + "5}}}").statusCode(200).body("IndexingRule.Rule.Probabilistic.DesiredSamplingPercentage",
                equalTo(5.0f));
        post("/UpdateIndexingRule", "{\"Name\":\"Other\",\"Rule\":{\"Probabilistic\":{\"DesiredSamplingPercentage\":"
                + "5}}}").statusCode(404);
    }

    @Test
    void transactionSearchRetrievesTraces() {
        post("/GetTraceSegmentDestination", "{}").statusCode(200).body("Destination", equalTo("XRay"));
        post("/StartTraceRetrieval", "{\"TraceIds\":[\"" + traceId() + "\"],\"StartTime\":" + (now() - 60)
                + ",\"EndTime\":" + now() + "}").statusCode(400);
        post("/UpdateTraceSegmentDestination", "{\"Destination\":\"CloudWatchLogs\"}").statusCode(400);
        given().header("X-Amz-Target", "Logs_20140328.PutResourcePolicy").contentType("application/x-amz-json-1.1")
                .body("{\"policyName\":\"xray-spans\",\"policyDocument\":\"{\\\"Version\\\":\\\"2012-10-17\\\","
                        + "\\\"Statement\\\":[{\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":{\\\"Service\\\":"
                        + "\\\"xray.amazonaws.com\\\"},\\\"Action\\\":\\\"logs:PutLogEvents\\\",\\\"Resource\\\":"
                        + "\\\"*\\\"}]}\"}").post("/").then().statusCode(200);
        post("/UpdateTraceSegmentDestination", "{\"Destination\":\"CloudWatchLogs\"}").statusCode(200)
                .body("Status", equalTo("PENDING"));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                post("/GetTraceSegmentDestination", "{}").body("Status", equalTo("ACTIVE")));
        String trace = traceId();
        put(requestTrace(trace, false, "cy"));
        given().header("X-Amz-Target", "Logs_20140328.FilterLogEvents").contentType("application/x-amz-json-1.1")
                .body("{\"logGroupName\":\"aws/spans\"}").post("/").then().statusCode(200)
                .body("events.message", hasItem(containsString(trace)));

        String token = post("/StartTraceRetrieval", "{\"TraceIds\":[\"" + trace + "\"],\"StartTime\":" + (now() - 60)
                + ",\"EndTime\":" + (now() + 60) + "}").statusCode(200).extract().path("RetrievalToken");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> post("/ListRetrievedTraces",
                "{\"RetrievalToken\":\"" + token + "\"}").statusCode(200).body("RetrievalStatus", equalTo("COMPLETE"))
                .body("Traces[0].Id", equalTo(trace)).body("Traces[0].Spans", hasSize(2)));
        post("/ListRetrievedTraces", "{\"RetrievalToken\":\"" + token + "\",\"TraceFormat\":\"OTEL\"}")
                .body("Traces[0].Spans[0].Document", containsString("spanId"));
        post("/GetRetrievedTracesGraph", "{\"RetrievalToken\":\"" + token + "\"}").statusCode(200)
                .body("Services.Service.Name", hasItem("orders"));
        post("/CancelTraceRetrieval", "{\"RetrievalToken\":\"" + token + "\"}").statusCode(400);
        post("/ListRetrievedTraces", "{\"RetrievalToken\":\"missing\"}").statusCode(404);
        post("/UpdateTraceSegmentDestination", "{\"Destination\":\"XRay\"}").statusCode(200);
    }

    @Test
    void faultSpikesOpenInsightsAnnouncedToEventBridge() {
        String queue = given().header("X-Amz-Target", "AmazonSQS.CreateQueue").contentType("application/x-amz-json-1.0")
                .body("{\"QueueName\":\"xray-insights\"}").post("/").then().statusCode(200).extract().path("QueueUrl");
        given().header("X-Amz-Target", "AWSEvents.PutRule").contentType("application/x-amz-json-1.1")
                .body("{\"Name\":\"xray-insights\",\"EventPattern\":\"{\\\"source\\\":[\\\"aws.xray\\\"]}\"}").post("/")
                .then().statusCode(200);
        given().header("X-Amz-Target", "AWSEvents.PutTargets").contentType("application/x-amz-json-1.1")
                .body("{\"Rule\":\"xray-insights\",\"Targets\":[{\"Id\":\"q\",\"Arn\":\"arn:aws:sqs:us-east-1:" + ACCOUNT
                        + ":xray-insights\"}]}").post("/").then().statusCode(200);
        post("/CreateGroup", "{\"GroupName\":\"payments\",\"FilterExpression\":\"service(\\\"payments\\\")\","
                + "\"InsightsConfiguration\":{\"InsightsEnabled\":true,\"NotificationsEnabled\":true}}").statusCode(200);
        for (int i = 0; i < 5; i++) {
            double start = now() - 0.2;
            put("{\"name\":\"payments\",\"id\":\"" + hex(16) + "\",\"trace_id\":\"" + traceId() + "\",\"start_time\":"
                    + start + ",\"end_time\":" + (start + 0.1) + ",\"fault\":true}");
        }
        double window = now();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> post("/InsightSummaries",
                "{\"GroupName\":\"payments\",\"StartTime\":" + (window - 600) + ",\"EndTime\":" + (now() + 60) + "}")
                .statusCode(200).body("InsightSummaries[0].State", equalTo("ACTIVE"))
                .body("InsightSummaries[0].RootCauseServiceId.Name", equalTo("payments")));
        String insight = post("/InsightSummaries", "{\"GroupName\":\"payments\",\"StartTime\":" + (window - 600)
                + ",\"EndTime\":" + (now() + 60) + "}").extract().path("InsightSummaries[0].InsightId");
        post("/Insight", "{\"InsightId\":\"" + insight + "\"}").statusCode(200)
                .body("Insight.Categories", equalTo(List.of("FAULT")))
                .body("Insight.RootCauseServiceRequestImpactStatistics.FaultCount", notNullValue());
        post("/InsightEvents", "{\"InsightId\":\"" + insight + "\"}").statusCode(200)
                .body("InsightEvents[0].Summary", containsString("payments"));
        post("/InsightImpactGraph", "{\"InsightId\":\"" + insight + "\",\"StartTime\":" + (window - 600)
                + ",\"EndTime\":" + (now() + 60) + "}").statusCode(200).body("Services.Name", hasItem("payments"));
        post("/Insight", "{\"InsightId\":\"missing\"}").statusCode(400);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<String> bodies = given().header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                    .contentType("application/x-amz-json-1.0").body("{\"QueueUrl\":\"" + queue
                            + "\",\"MaxNumberOfMessages\":10}").post("/").then().extract().path("Messages.Body");
            assertTrue(bodies != null && bodies.stream().anyMatch(b -> b.contains("AWS X-Ray Insight Update")
                    && b.contains("payments")), String.valueOf(bodies));
        });
        // The insight closes once the service stops faulting.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> post("/Insight", "{\"InsightId\":\"" + insight
                + "\"}").body("Insight.State", equalTo("CLOSED")));
        post("/DeleteGroup", "{\"GroupName\":\"payments\"}").statusCode(200);
    }
}
