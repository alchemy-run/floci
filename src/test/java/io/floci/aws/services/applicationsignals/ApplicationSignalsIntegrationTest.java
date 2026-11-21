package io.floci.aws.services.applicationsignals;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;

/**
 * Application Signals: services, operations, dependencies and dependents discovered from X-Ray traces; SLOs whose
 * budget reports are computed from real CloudWatch metric data, with exclusion windows; instrumentation
 * configurations and their status events; grouping configurations; tags.
 */
@QuarkusTest
class ApplicationSignalsIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification signals() {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260101/us-east-1/application-signals/aws4_request").contentType("application/json");
    }

    private static String hex(int length) {
        byte[] bytes = new byte[length / 2];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    private static String quote(String json) {
        return "\"" + json.replace("\"", "\\\"") + "\"";
    }

    /** A checkout service (on Lambda) that calls the payments service and DynamoDB. */
    private static void trace() {
        String trace = "1-" + Long.toHexString((long) now()) + "-" + hex(24);
        double start = now() - 2;
        String checkout = hex(16);
        String call = hex(16);
        String a = "{\"name\":\"checkout\",\"id\":\"" + checkout + "\",\"trace_id\":\"" + trace + "\",\"start_time\":"
                + start + ",\"end_time\":" + (start + 0.5) + ",\"origin\":\"AWS::Lambda::Function\",\"http\":"
                + "{\"request\":{\"method\":\"POST\",\"url\":\"https://shop.example.com/orders\"},\"response\":"
                + "{\"status\":200}},\"subsegments\":[{\"name\":\"payments\",\"id\":\"" + call + "\",\"start_time\":"
                + (start + 0.1) + ",\"end_time\":" + (start + 0.3) + ",\"namespace\":\"remote\",\"http\":{\"request\":"
                + "{\"method\":\"POST\",\"url\":\"https://pay.example.com/charge\"}}},{\"name\":\"DynamoDB\",\"id\":\""
                + hex(16) + "\",\"start_time\":" + (start + 0.31) + ",\"end_time\":" + (start + 0.4)
                + ",\"namespace\":\"aws\"}]}";
        String b = "{\"name\":\"payments\",\"id\":\"" + hex(16) + "\",\"trace_id\":\"" + trace + "\",\"parent_id\":\""
                + call + "\",\"start_time\":" + (start + 0.11) + ",\"end_time\":" + (start + 0.29)
                + ",\"origin\":\"AWS::ECS::Container\",\"http\":{\"request\":{\"method\":\"POST\",\"url\":"
                + "\"https://pay.example.com/charge\"},\"response\":{\"status\":200}}}";
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/xray/"
                        + "aws4_request").contentType("application/json")
                .body("{\"TraceSegmentDocuments\":[" + quote(a) + "," + quote(b) + "]}").post("/TraceSegments").then()
                .statusCode(200).body("UnprocessedTraceSegments", hasSize(0));
    }

    private static String window() {
        return "StartTime=" + Instant.ofEpochSecond((long) now() - 600) + "&EndTime="
                + Instant.ofEpochSecond((long) now() + 60);
    }

    @Test
    void servicesAreDiscoveredFromTraces() {
        trace();
        String checkout = "{\"KeyAttributes\":{\"Type\":\"Service\",\"Name\":\"checkout\",\"Environment\":"
                + "\"lambda:default\"}}";
        signals().get("/services?" + window()).then().statusCode(200)
                .body("ServiceSummaries.KeyAttributes.Name", hasItem("checkout"))
                .body("ServiceSummaries.find { it.KeyAttributes.Name == 'payments' }.KeyAttributes.Environment",
                        equalTo("ecs:default"));
        signals().body(checkout).post("/service?" + window()).then().statusCode(200)
                .body("Service.KeyAttributes.Name", equalTo("checkout"))
                .body("Service.MetricReferences.MetricType", hasItem("LATENCY"));
        signals().body("{\"KeyAttributes\":{\"Type\":\"Service\",\"Name\":\"nope\",\"Environment\":\"x\"}}")
                .post("/service?" + window()).then().statusCode(200).body("Service.KeyAttributes", equalTo(null));
        signals().body(checkout).post("/service-operations?" + window()).then().statusCode(200)
                .body("ServiceOperations.Name", equalTo(List.of("POST /orders")));
        signals().body(checkout).post("/service-dependencies?" + window()).then().statusCode(200)
                .body("ServiceDependencies.DependencyKeyAttributes.Name", hasItem("payments"))
                .body("ServiceDependencies.DependencyKeyAttributes.Name", hasItem("AWS::DynamoDB"))
                .body("ServiceDependencies.find { it.DependencyKeyAttributes.Name == 'payments' }"
                        + ".DependencyOperationName", equalTo("POST /charge"));
        signals().body("{\"KeyAttributes\":{\"Type\":\"Service\",\"Name\":\"payments\",\"Environment\":"
                + "\"ecs:default\"}}").post("/service-dependents?" + window()).then().statusCode(200)
                .body("ServiceDependents.DependentKeyAttributes.Name", equalTo(List.of("checkout")));
        signals().get("/services?StartTime=" + Instant.ofEpochSecond((long) now() - 3 * 86400) + "&EndTime="
                + Instant.ofEpochSecond((long) now())).then().statusCode(400);
    }

    @Test
    void sloBudgetsComeFromMetricData() {
        // Six minutes of latency, two of them over the 100ms threshold.
        StringBuilder data = new StringBuilder();
        long minute = ((long) now() / 60 - 7) * 60;
        double[] latencies = {20, 30, 250, 40, 300, 25};
        for (int i = 0; i < latencies.length; i++) {
            data.append("&MetricData.member.").append(i + 1).append(".MetricName=Latency")
                    .append("&MetricData.member.").append(i + 1).append(".Value=").append(latencies[i])
                    .append("&MetricData.member.").append(i + 1).append(".Timestamp=")
                    .append(Instant.ofEpochSecond(minute + 60L * i))
                    .append("&MetricData.member.").append(i + 1).append(".Dimensions.member.1.Name=Api")
                    .append("&MetricData.member.").append(i + 1).append(".Dimensions.member.1.Value=shop");
        }
        given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/monitoring/"
                        + "aws4_request").contentType("application/x-www-form-urlencoded")
                .body("Action=PutMetricData&Version=2010-08-01&Namespace=Shop" + data).post("/").then()
                .statusCode(200);
        String sli = "{\"SliMetricConfig\":{\"MetricDataQueries\":[{\"Id\":\"m1\",\"MetricStat\":{\"Metric\":"
                + "{\"Namespace\":\"Shop\",\"MetricName\":\"Latency\",\"Dimensions\":[{\"Name\":\"Api\",\"Value\":"
                + "\"shop\"}]},\"Period\":60,\"Stat\":\"Average\"},\"ReturnData\":true}]},\"MetricThreshold\":100,"
                + "\"ComparisonOperator\":\"LessThanOrEqualTo\"}";
        signals().body("{\"Name\":\"latency\",\"Goal\":{\"Interval\":{\"RollingInterval\":{\"DurationUnit\":\"HOUR\","
                        + "\"Duration\":1}},\"AttainmentGoal\":99},\"SliConfig\":" + sli + ",\"Tags\":[{\"Key\":\"team\","
                        + "\"Value\":\"web\"}]}").post("/slo").then().statusCode(200)
                .body("Slo.EvaluationType", equalTo("PeriodBased"))
                .body("Slo.MetricSourceType", equalTo("CloudWatchMetric"))
                .body("Slo.Goal.WarningThreshold", equalTo(50.0f))
                .body("Slo.Arn", equalTo("arn:aws:application-signals:us-east-1:" + ACCOUNT + ":slo/latency"));
        signals().body("{\"Name\":\"latency\",\"SliConfig\":" + sli + "}").post("/slo").then().statusCode(409);
        signals().body("{\"Name\":\"both\",\"SliConfig\":" + sli + ",\"RequestBasedSliConfig\":{}}").post("/slo")
                .then().statusCode(400);

        // 4 of 6 periods meet the threshold; a 99% goal over an hour allows no bad minute, so it is breached.
        ValidatableResponse report = signals().body("{\"Timestamp\":" + now() + ",\"SloIds\":[\"latency\","
                + "\"missing\"]}").post("/budget-report").then().statusCode(200);
        report.body("Reports[0].Attainment", equalTo(66.6667f)).body("Reports[0].BudgetStatus", equalTo("BREACHED"))
                .body("Reports[0].BudgetSecondsRemaining", lessThan(0))
                .body("Errors[0].ErrorCode", equalTo("ResourceNotFoundException"));

        // Excluding the window that holds both bad minutes makes the SLO healthy again.
        String exclusion = "{\"StartTime\":" + (minute + 120) + ",\"Window\":{\"DurationUnit\":\"MINUTE\","
                + "\"Duration\":3},\"Reason\":\"deploy\"}";
        signals().body("{\"SloIds\":[\"latency\"],\"AddExclusionWindows\":[" + exclusion + "]}")
                .patch("/exclusion-windows").then().statusCode(200).body("SloIds", equalTo(List.of("latency")));
        signals().get("/slo/latency/exclusion-windows").then().statusCode(200).body("ExclusionWindows", hasSize(1));
        signals().body("{\"Timestamp\":" + now() + ",\"SloIds\":[\"latency\"]}").post("/budget-report").then()
                .body("Reports[0].Attainment", equalTo(100.0f)).body("Reports[0].BudgetStatus", equalTo("OK"));
        signals().body("{\"SloIds\":[\"latency\"],\"RemoveExclusionWindows\":[" + exclusion + "]}")
                .patch("/exclusion-windows").then().statusCode(200);
        signals().get("/slo/latency/exclusion-windows").then().body("ExclusionWindows", hasSize(0));

        // An SLO with no data reports insufficient data.
        signals().body("{\"Name\":\"quiet\",\"SliConfig\":" + sli.replace("\"shop\"", "\"none\"") + "}").post("/slo")
                .then().statusCode(200);
        signals().body("{\"Timestamp\":" + now() + ",\"SloIds\":[\"quiet\"]}").post("/budget-report").then()
                .body("Reports[0].BudgetStatus", equalTo("INSUFFICIENT_DATA"));

        String arn = "arn:aws:application-signals:us-east-1:" + ACCOUNT + ":slo/latency";
        signals().queryParam("ResourceArn", arn).get("/tags").then().statusCode(200)
                .body("Tags[0].Key", equalTo("team"));
        signals().body("{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}")
                .post("/tag-resource").then().statusCode(200);
        signals().body("{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"team\"]}").post("/untag-resource").then()
                .statusCode(200);
        signals().queryParam("ResourceArn", arn).get("/tags").then().body("Tags.Key", equalTo(List.of("env")));
        signals().queryParam("ResourceArn", arn + "x").get("/tags").then().statusCode(404);

        signals().body("{\"Description\":\"p50\",\"Goal\":{\"AttainmentGoal\":90}}").patch("/slo/latency").then()
                .statusCode(200).body("Slo.Description", equalTo("p50")).body("Slo.Goal.AttainmentGoal", equalTo(90.0f))
                .body("Slo.Goal.Interval.RollingInterval.DurationUnit", equalTo("HOUR"));
        signals().body("{}").post("/slos").then().statusCode(200).body("SloSummaries.Name",
                equalTo(List.of("latency", "quiet")));
        signals().delete("/slo/latency").then().statusCode(200);
        signals().delete("/slo/quiet").then().statusCode(200);
        signals().get("/slo/latency").then().statusCode(404).body("__type", containsString("ResourceNotFound"));
    }

    @Test
    void instrumentationConfigurationsAndGrouping() {
        String id = "\"InstrumentationType\":\"PROBE\",\"Service\":\"cart\",\"Environment\":\"prod\","
                + "\"SignalType\":\"SNAPSHOT\"";
        String location = "{\"CodeLocation\":{\"Language\":\"Python\",\"MethodName\":\"handler\",\"FilePath\":"
                + "\"app/main.py\",\"LineNumber\":12}}";
        String hash = signals().body("{" + id + ",\"Location\":" + location + ",\"CaptureConfiguration\":"
                        + "{\"CodeCapture\":{\"CaptureLimits\":{\"MaxHits\":3}}}}").post("/create-instrumentation-configuration")
                .then().statusCode(200).body("ARN", containsString(":instrumentationConfig/")).extract()
                .path("LocationHash");
        signals().body("{" + id + ",\"Location\":" + location + ",\"CaptureConfiguration\":{\"CodeCapture\":"
                + "{\"CaptureLimits\":{}}}}").post("/create-instrumentation-configuration").then().statusCode(409);
        signals().body("{" + id + ",\"LocationIdentifier\":" + location + "}")
                .post("/get-instrumentation-configuration").then().statusCode(200)
                .body("Configuration.LocationHash", equalTo(hash));
        signals().body("{\"Service\":\"cart\",\"Environment\":\"prod\",\"InstrumentationType\":\"PROBE\"}")
                .post("/list-instrumentation-configurations").then().statusCode(200).body("Changed", equalTo(true))
                .body("LatestConfigurations.LocationHash", equalTo(List.of(hash)));
        signals().body("{" + id + ",\"LocationIdentifier\":{\"LocationHash\":\"" + hash + "\"}}")
                .post("/get-instrumentation-configuration-status").then().statusCode(200)
                .body("Status", equalTo("READY")).body("Events", hasSize(0));
        signals().body("{\"Service\":\"cart\",\"Environment\":\"prod\",\"Configurations\":[{\"InstrumentationType\":"
                + "\"PROBE\",\"SignalType\":\"SNAPSHOT\",\"LocationHash\":\"" + hash + "\",\"Status\":\"ERROR\","
                + "\"Time\":" + now() + ",\"ErrorCause\":\"LINE_NOT_EXECUTABLE\"},{\"InstrumentationType\":\"PROBE\","
                + "\"SignalType\":\"SNAPSHOT\",\"LocationHash\":\"0000000000000000\",\"Status\":\"ACTIVE\",\"Time\":" + now()
                + "}]}").post("/report-instrumentation-configuration-status").then().statusCode(200)
                .body("UnprocessedStatusEvents.LocationHash", equalTo(List.of("0000000000000000")));
        signals().body("{" + id + ",\"LocationIdentifier\":{\"LocationHash\":\"" + hash + "\"}}")
                .post("/get-instrumentation-configuration-status").then().body("Status", equalTo("ERROR"))
                .body("Events[0].ErrorCause", equalTo("LINE_NOT_EXECUTABLE"));
        signals().body("{\"DeletionTarget\":{\"Scope\":{\"Service\":\"cart\",\"Environment\":\"prod\","
                + "\"InstrumentationType\":\"PROBE\"}}}").post("/batch-delete-instrumentation-configurations").then()
                .statusCode(200).body("DeletedCount", equalTo(1));
        signals().body("{" + id + ",\"LocationIdentifier\":{\"LocationHash\":\"" + hash + "\"}}")
                .post("/get-instrumentation-configuration").then().statusCode(404);

        signals().get("/grouping-attribute-definitions").then().statusCode(404);
        signals().body("{}").post("/grouping-attribute-definitions").then().statusCode(200)
                .body("GroupingAttributeDefinitions", hasSize(0));
        signals().body("{\"GroupingAttributeDefinitions\":[{\"GroupingName\":\"Team\",\"GroupingSourceKeys\":"
                + "[\"Tag.team\"],\"DefaultGroupingValue\":\"none\"}]}").put("/grouping-configuration").then()
                .statusCode(200).body("GroupingConfiguration.GroupingAttributeDefinitions[0].GroupingName",
                        equalTo("Team"));
        signals().body("{}").post("/grouping-attribute-definitions").then()
                .body("GroupingAttributeDefinitions.GroupingName", equalTo(List.of("Team")));
        signals().delete("/grouping-configuration").then().statusCode(200);
        signals().body("{}").post("/grouping-attribute-definitions").then()
                .body("GroupingAttributeDefinitions", hasSize(0));
        signals().post("/start-discovery").then().statusCode(200);
        signals().post("/start-discovery").then().statusCode(200);
    }
}
