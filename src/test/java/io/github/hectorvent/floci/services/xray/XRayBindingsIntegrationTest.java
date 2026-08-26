package io.github.hectorvent.floci.services.xray;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the X-Ray restJson1 operations Alchemy Bindings.test.ts exercises. */
@QuarkusTest
class XRayBindingsIntegrationTest {

    private static final String EAST = "us-east-1";
    private static final String ACCOUNT = "000000002401";

    @Inject
    XRayService xRayService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getTraceSummariesReturnsEmptyListWhenNothingIsIngested() {
        double end = System.currentTimeMillis() / 1000.0;
        double start = end - 600;
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"StartTime\":" + start + ",\"EndTime\":" + end + "}")
                .when()
                .post("/TraceSummaries")
                .then()
                .statusCode(200)
                .body("TraceSummaries", hasSize(0));
    }

    @Test
    void putTraceSegmentsRoundTripsThroughSummariesAndBatchGet() {
        String authorization = auth(ACCOUNT, EAST);
        double now = System.currentTimeMillis() / 1000.0;
        String epochHex = Long.toHexString((long) now);
        String random24 = UUID.randomUUID().toString().replace("-", "") + "abcd";
        random24 = random24.substring(0, 24);
        String segmentId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceId = "1-" + epochHex + "-" + random24;
        String document = "{"
                + "\"name\":\"alchemy-xray-binding-test\","
                + "\"id\":\"" + segmentId + "\","
                + "\"trace_id\":\"" + traceId + "\","
                + "\"start_time\":" + (now - 0.5) + ","
                + "\"end_time\":" + now
                + "}";
        String escaped = document.replace("\"", "\\\"");

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TraceSegmentDocuments\":[\"" + escaped + "\"]}")
                .when()
                .post("/TraceSegments")
                .then()
                .statusCode(200)
                .body("UnprocessedTraceSegments", hasSize(0));

        List<String> ids = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StartTime\":" + (now - 60) + ",\"EndTime\":" + (now + 60)
                        + ",\"FilterExpression\":\"service(\\\"alchemy-xray-binding-test\\\")\"}")
                .when()
                .post("/TraceSummaries")
                .then()
                .statusCode(200)
                .extract()
                .path("TraceSummaries.Id");
        assertTrue(ids.contains(traceId));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TraceIds\":[\"" + traceId + "\"]}")
                .when()
                .post("/Traces")
                .then()
                .statusCode(200)
                .body("Traces[0].Id", equalTo(traceId))
                .body("Traces[0].Segments.size()", greaterThanOrEqualTo(1));
    }

    @Test
    void batchGetTracesOnAMissingIdReturnsEmptyTraces() {
        double now = System.currentTimeMillis() / 1000.0;
        String epochHex = Long.toHexString((long) now);
        String missing = "1-" + epochHex + "-abcdef0123456789abcdef01";
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"TraceIds\":[\"" + missing + "\"]}")
                .when()
                .post("/Traces")
                .then()
                .statusCode(200)
                .body("Traces", hasSize(0));
    }

    @Test
    void putTelemetryRecordsSucceeds() {
        given()
                .contentType("application/json")
                .header("Authorization", auth(ACCOUNT, EAST))
                .body("{\"TelemetryRecords\":[{\"Timestamp\":" + (System.currentTimeMillis() / 1000.0)
                        + ",\"SegmentsReceivedCount\":1,\"SegmentsSentCount\":1}]}")
                .when()
                .post("/TelemetryRecords")
                .then()
                .statusCode(200);
    }

    @Test
    void samplingProtocolIncludesDefaultRuleAndReturnsATarget() {
        String authorization = auth(ACCOUNT, EAST);
        List<String> names = given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetSamplingRules")
                .then()
                .statusCode(200)
                .extract()
                .path("SamplingRuleRecords.SamplingRule.RuleName");
        assertTrue(names.contains("Default"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/SamplingStatisticSummaries")
                .then()
                .statusCode(200);

        String clientId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"SamplingStatisticsDocuments\":[{"
                        + "\"RuleName\":\"Default\","
                        + "\"ClientID\":\"" + clientId + "\","
                        + "\"Timestamp\":" + (System.currentTimeMillis() / 1000.0) + ","
                        + "\"RequestCount\":1,"
                        + "\"SampledCount\":1"
                        + "}]}")
                .when()
                .post("/SamplingTargets")
                .then()
                .statusCode(200)
                .body("SamplingTargetDocuments.size()", greaterThanOrEqualTo(1))
                .body("SamplingTargetDocuments.RuleName", hasItem("Default"));
    }

    @Test
    void serviceGraphAndTimeSeriesAcceptEmptyWindows() {
        String authorization = auth(ACCOUNT, EAST);
        double end = System.currentTimeMillis() / 1000.0;
        double start = end - 600;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StartTime\":" + start + ",\"EndTime\":" + end + "}")
                .when()
                .post("/ServiceGraph")
                .then()
                .statusCode(200)
                .body("Services.size()", greaterThanOrEqualTo(0));

        double now = System.currentTimeMillis() / 1000.0;
        String epochHex = Long.toHexString((long) now);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TraceIds\":[\"1-" + epochHex + "-abcdef0123456789abcdef01\"]}")
                .when()
                .post("/TraceGraph")
                .then()
                .statusCode(200)
                .body("Services", hasSize(0));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"StartTime\":" + start + ",\"EndTime\":" + end + ",\"Period\":60}")
                .when()
                .post("/TimeSeriesServiceStatistics")
                .then()
                .statusCode(200)
                .body("TimeSeriesServiceStatistics.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void insightLookupsAnswerWithTypedValidationErrors() {
        String authorization = auth(ACCOUNT, EAST);
        double end = System.currentTimeMillis() / 1000.0;
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"GroupName\":\"Default\",\"StartTime\":" + (end - 86400) + ",\"EndTime\":" + end + "}")
                .when()
                .post("/InsightSummaries")
                .then()
                .statusCode(200)
                .body("InsightSummaries.size()", greaterThanOrEqualTo(0));

        String insightId = "00000000-0000-0000-0000-000000000000";
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"InsightId\":\"" + insightId + "\"}")
                .when()
                .post("/Insight")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"InsightId\":\"" + insightId + "\"}")
                .when()
                .post("/InsightEvents")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"InsightId\":\"" + insightId + "\",\"StartTime\":" + (end - 3600)
                        + ",\"EndTime\":" + end + "}")
                .when()
                .post("/InsightImpactGraph")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    void traceRetrievalBindingsOnTheDefaultXRayDestination() {
        String authorization = auth(ACCOUNT, EAST);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{}")
                .when()
                .post("/GetTraceSegmentDestination")
                .then()
                .statusCode(200)
                .body("Destination", equalTo("XRay"));

        double end = System.currentTimeMillis() / 1000.0;
        String epochHex = Long.toHexString((long) end);
        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"TraceIds\":[\"1-" + epochHex + "-abcdef0123456789abcdef01\"],"
                        + "\"StartTime\":" + (end - 3600) + ",\"EndTime\":" + end + "}")
                .when()
                .post("/StartTraceRetrieval")
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("{\"RetrievalToken\":\"alchemy-nonexistent-token\"}")
                .when()
                .post("/ListRetrievedTraces")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void recordLambdaInvocationIsQueryableByServiceName() {
        String functionName = "XRayBindings-XRayTestFunction-" + UUID.randomUUID().toString().substring(0, 8);
        String account = "000000000000";
        xRayService.recordLambdaInvocation(EAST, functionName, "arn:aws:lambda:" + EAST + ":" + account
                + ":function:" + functionName);
        double end = System.currentTimeMillis() / 1000.0 + 5;
        List<Map<String, Object>> summaries = given()
                .contentType("application/json")
                .header("Authorization", auth(account, EAST))
                .body("{\"StartTime\":" + (end - 600) + ",\"EndTime\":" + end
                        + ",\"FilterExpression\":\"service(\\\"" + functionName + "\\\")\"}")
                .when()
                .post("/TraceSummaries")
                .then()
                .statusCode(200)
                .extract()
                .path("TraceSummaries");
        assertEquals(1, summaries.size());
    }

    private static String auth(String accountId, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260205/" + region
                + "/xray/aws4_request";
    }
}
