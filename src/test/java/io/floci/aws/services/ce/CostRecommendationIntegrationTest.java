package io.floci.aws.services.ce;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cost Explorer forecasts, comparisons, usage record estimates, commitment and rightsizing recommendations,
 * commitment purchase analyses, cost allocation tags and anomaly queries, driven by a running, tagged t3.micro.
 */
@QuarkusTest
class CostRecommendationIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ce/aws4_request";
    private static final String EC2_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ec2/aws4_request";
    private static final String CW_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/monitoring/aws4_request";
    private static final String TAG_KEY = "alloc-" + UUID.randomUUID().toString().substring(0, 8);
    private static String instanceId;
    private static int port;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** Launches the tagged instance on first use: Quarkus points RestAssured at the app only once a test runs. */
    private static synchronized String instance() {
        if (instanceId == null) {
            port = RestAssured.port;
            instanceId = launch();
        }
        return instanceId;
    }

    private static String launch() {
        return given().header("Authorization", EC2_AUTH)
                .formParam("Action", "RunInstances").formParam("ImageId", "ami-ce-recommendations")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("TagSpecification.1.ResourceType", "instance")
                .formParam("TagSpecification.1.Tag.1.Key", TAG_KEY)
                .formParam("TagSpecification.1.Tag.1.Value", "on")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @AfterAll
    static void terminate() {
        if (instanceId == null) {
            return;
        }
        // Quarkus resets RestAssured's port between tests, so reach the app on the port the instance was made on.
        given().port(port).header("Authorization", EC2_AUTH).formParam("Action", "TerminateInstances")
                .formParam("InstanceId.1", instanceId).post("/");
    }

    static ValidatableResponse call(String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSInsightsIndexService." + action)
                .header("Authorization", AUTH)
                .body(body).post("/").then();
    }

    private static String month(int offset) {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).plusMonths(offset).toString();
    }

    @Test
    void forecastsProjectTheRunRate() {
        instance();
        String period = "\"TimePeriod\":{\"Start\":\"" + month(1) + "\",\"End\":\"" + month(2) + "\"}";
        String amount = call("GetCostForecast", "{" + period + ",\"Metric\":\"UNBLENDED_COST\",\"Granularity\":"
                + "\"MONTHLY\",\"Filter\":{\"Tags\":{\"Key\":\"" + TAG_KEY + "\",\"Values\":[\"on\"]}}}")
                .statusCode(200).body("ForecastResultsByTime", hasSize(1))
                .body("ForecastResultsByTime[0].TimePeriod.Start", equalTo(month(1)))
                .body("Total.Unit", equalTo("USD")).extract().path("Total.Amount");
        assertTrue(Double.parseDouble(amount) > 0, amount);
        call("GetCostForecast", "{" + period + ",\"Metric\":\"UNBLENDED_COST\",\"Granularity\":\"MONTHLY\","
                + "\"Filter\":{\"Tags\":{\"Key\":\"" + TAG_KEY + "\",\"Values\":[\"off\"]}}}").statusCode(400)
                .body("__type", containsString("DataUnavailableException"));
        call("GetCostForecast", "{" + period + ",\"Metric\":\"UNBLENDED_COST\",\"Granularity\":\"HOURLY\"}")
                .statusCode(400).body("__type", containsString("ValidationException"));
        call("GetUsageForecast", "{" + period + ",\"Metric\":\"USAGE_QUANTITY\",\"Granularity\":\"MONTHLY\"}")
                .statusCode(400).body("message", containsString("USAGE_TYPE"));
        call("GetUsageForecast", "{" + period + ",\"Metric\":\"USAGE_QUANTITY\",\"Granularity\":\"MONTHLY\","
                + "\"Filter\":{\"And\":[{\"Dimensions\":{\"Key\":\"USAGE_TYPE\",\"Values\":[\"BoxUsage:t3.micro\"]}},"
                + "{\"Tags\":{\"Key\":\"" + TAG_KEY + "\",\"Values\":[\"on\"]}}]}}").statusCode(200)
                .body("Total.Unit", equalTo("Hrs"));
    }

    @Test
    void comparesMonthsAndExplainsDrivers() {
        instance();
        String periods = "\"BaselineTimePeriod\":{\"Start\":\"" + month(-2) + "\",\"End\":\"" + month(-1) + "\"},"
                + "\"ComparisonTimePeriod\":{\"Start\":\"" + month(-1) + "\",\"End\":\"" + month(0) + "\"}";
        call("GetCostAndUsageComparisons", "{" + periods + ",\"MetricForComparison\":\"UnblendedCost\","
                + "\"GroupBy\":[{\"Type\":\"DIMENSION\",\"Key\":\"SERVICE\"}]}").statusCode(200)
                .body("CostAndUsageComparisons.CostAndUsageSelector.Dimensions.Values.flatten()", hasItem("AmazonEC2"))
                .body("TotalCostAndUsage.UnblendedCost.Unit", equalTo("USD"));
        call("GetCostComparisonDrivers", "{" + periods + ",\"MetricForComparison\":\"UnblendedCost\"}")
                .statusCode(200)
                .body("CostComparisonDrivers.CostSelector.Dimensions.Values.flatten()", hasItem("AmazonEC2"));
        call("GetCostAndUsageComparisons", "{\"BaselineTimePeriod\":{\"Start\":\"" + month(-2) + "\",\"End\":\""
                + month(0) + "\"},\"ComparisonTimePeriod\":{\"Start\":\"" + month(-1) + "\",\"End\":\"" + month(0)
                + "\"},\"MetricForComparison\":\"UnblendedCost\"}").statusCode(400)
                .body("message", containsString("one calendar month"));
    }

    @Test
    void estimatesUsageRecords() {
        instance();
        call("GetApproximateUsageRecords", "{\"Granularity\":\"DAILY\",\"ApproximationDimension\":\"SERVICE\"}")
                .statusCode(400).body("message", containsString("input combination"));
        int records = call("GetApproximateUsageRecords", "{\"Granularity\":\"HOURLY\",\"ApproximationDimension\":"
                + "\"SERVICE\",\"Services\":[\"AmazonEC2\"]}").statusCode(200)
                .extract().path("Services.AmazonEC2");
        assertTrue(records >= 720, Integer.toString(records));
    }

    @Test
    void recommendsCommitmentsForSteadyUsage() {
        instance();
        call("GetReservationPurchaseRecommendation", "{\"Service\":\"Amazon Elastic Compute Cloud - Compute\"}")
                .statusCode(200)
                .body("Recommendations[0].RecommendationDetails.InstanceDetails.EC2InstanceDetails.InstanceType",
                        hasItem("t3.micro"))
                .body("Recommendations[0].TermInYears", equalTo("ONE_YEAR"));
        call("GetReservationPurchaseRecommendation", "{\"Service\":\"Amazon Redshift\"}").statusCode(200)
                .body("Recommendations", hasSize(0));
        call("GetReservationPurchaseRecommendation", "{\"Service\":\"Amazon S3\"}").statusCode(400);

        String generation = call("StartSavingsPlansPurchaseRecommendationGeneration", "{}").statusCode(200)
                .extract().path("RecommendationId");
        call("ListSavingsPlansPurchaseRecommendationGeneration", "{}").statusCode(200)
                .body("GenerationSummaryList.find { it.RecommendationId == '" + generation + "' }.GenerationStatus",
                        equalTo("SUCCEEDED"));
        String detailId = call("GetSavingsPlansPurchaseRecommendation", "{\"SavingsPlansType\":\"COMPUTE_SP\","
                + "\"TermInYears\":\"ONE_YEAR\",\"PaymentOption\":\"NO_UPFRONT\",\"LookbackPeriodInDays\":"
                + "\"SEVEN_DAYS\"}").statusCode(200)
                .body("Metadata.RecommendationId", equalTo(generation))
                .extract().path("SavingsPlansPurchaseRecommendation.SavingsPlansPurchaseRecommendationDetails[0]"
                        + ".RecommendationDetailId");
        call("GetSavingsPlanPurchaseRecommendationDetails", "{\"RecommendationDetailId\":\"" + detailId + "\"}")
                .statusCode(200).body("RecommendationDetailData.SavingsPlansType", equalTo("COMPUTE_SP"))
                .body("RecommendationDetailData.MetricsOverLookbackPeriod", hasSize(7));
        call("GetSavingsPlanPurchaseRecommendationDetails", "{\"RecommendationDetailId\":\""
                + UUID.randomUUID() + "\"}").statusCode(400).body("__type", containsString("DataUnavailable"));

        String analysis = call("StartCommitmentPurchaseAnalysis", "{\"CommitmentPurchaseAnalysisConfiguration\":"
                + "{\"SavingsPlansPurchaseAnalysisConfiguration\":{\"AnalysisType\":\"MAX_SAVINGS\","
                + "\"SavingsPlansToAdd\":[{\"PaymentOption\":\"NO_UPFRONT\",\"SavingsPlansType\":\"COMPUTE_SP\","
                + "\"TermInYears\":\"ONE_YEAR\"}],\"LookBackTimePeriod\":{\"Start\":\""
                + LocalDate.now(ZoneOffset.UTC).minusDays(7) + "\",\"End\":\"" + LocalDate.now(ZoneOffset.UTC)
                + "\"}}}}").statusCode(200).extract().path("AnalysisId");
        call("GetCommitmentPurchaseAnalysis", "{\"AnalysisId\":\"" + analysis + "\"}").statusCode(200)
                .body("AnalysisStatus", equalTo("SUCCEEDED"))
                .body("AnalysisDetails.SavingsPlansPurchaseAnalysisDetails.LookbackPeriodInHours", equalTo("168"));
        call("ListCommitmentPurchaseAnalyses", "{}").statusCode(200)
                .body("AnalysisSummaryList.AnalysisId", hasItem(analysis));
        call("GetCommitmentPurchaseAnalysis", "{\"AnalysisId\":\"" + UUID.randomUUID() + "\"}").statusCode(400)
                .body("__type", containsString("AnalysisNotFoundException"));
    }

    @Test
    void rightsizesIdleInstancesFromCloudWatch() {
        instance();
        call("GetRightsizingRecommendation", "{\"Service\":\"AmazonRDS\"}").statusCode(400);
        call("GetRightsizingRecommendation", "{\"Service\":\"AmazonEC2\"}").statusCode(200)
                .body("RightsizingRecommendations.CurrentInstance.ResourceId", not(hasItem(instanceId)));
        given().header("Authorization", CW_AUTH).formParam("Action", "PutMetricData")
                .formParam("Namespace", "AWS/EC2")
                .formParam("MetricData.member.1.MetricName", "CPUUtilization")
                .formParam("MetricData.member.1.Dimensions.member.1.Name", "InstanceId")
                .formParam("MetricData.member.1.Dimensions.member.1.Value", instanceId)
                .formParam("MetricData.member.1.Value", "0.4")
                .formParam("MetricData.member.1.Unit", "Percent")
                .formParam("MetricData.member.1.Timestamp", Instant.now().minusSeconds(3600).toString())
                .post("/").then().statusCode(200);
        call("GetRightsizingRecommendation", "{\"Service\":\"AmazonEC2\"}").statusCode(200)
                .body("RightsizingRecommendations.find { it.CurrentInstance.ResourceId == '" + instanceId
                        + "' }.RightsizingType", equalTo("TERMINATE"))
                .body("Configuration.RecommendationTarget", equalTo("SAME_INSTANCE_FAMILY"));
    }

    @Test
    void costAllocationTagsAndBackfills() {
        instance();
        call("ListCostAllocationTags", "{\"TagKeys\":[\"" + TAG_KEY + "\"]}").statusCode(200)
                .body("CostAllocationTags[0].Status", equalTo("Inactive"))
                .body("CostAllocationTags[0].Type", equalTo("UserDefined"));
        call("UpdateCostAllocationTagsStatus", "{\"CostAllocationTagsStatus\":[{\"TagKey\":\"" + TAG_KEY
                + "\",\"Status\":\"Active\"},{\"TagKey\":\"never-used\",\"Status\":\"Active\"}]}").statusCode(200)
                .body("Errors.TagKey", equalTo(List.of("never-used")));
        call("ListCostAllocationTags", "{\"Status\":\"Active\"}").statusCode(200)
                .body("CostAllocationTags.TagKey", hasItem(TAG_KEY));

        call("StartCostAllocationTagBackfill", "{\"BackfillFrom\":\"" + month(-1) + "T00:00:00Z\"}")
                .statusCode(200).body("BackfillRequest.BackfillStatus", equalTo("SUCCEEDED"));
        call("StartCostAllocationTagBackfill", "{\"BackfillFrom\":\"" + month(-1) + "T00:00:00Z\"}")
                .statusCode(400).body("__type", containsString("BackfillLimitExceededException"));
        call("ListCostAllocationTagBackfillHistory", "{}").statusCode(200)
                .body("BackfillRequests[0].BackfillFrom", equalTo(month(-1) + "T00:00:00Z"));
    }

    @Test
    void anomalyQueries() {
        instance();
        call("GetAnomalies", "{\"DateInterval\":{\"StartDate\":\"" + LocalDate.now(ZoneOffset.UTC).minusDays(5)
                + "\"}}").statusCode(200).body("Anomalies", hasSize(0));
        call("GetAnomalies", "{\"DateInterval\":{\"StartDate\":\"" + LocalDate.now(ZoneOffset.UTC).minusDays(100)
                + "\"}}").statusCode(400).body("message", containsString("90 days"));
        call("GetAnomalies", "{\"MonitorArn\":\"arn:aws:ce::000000000000:anomalymonitor/" + UUID.randomUUID()
                + "\",\"DateInterval\":{\"StartDate\":\"" + LocalDate.now(ZoneOffset.UTC) + "\"}}").statusCode(400)
                .body("__type", containsString("UnknownMonitorException"));
        call("ProvideAnomalyFeedback", "{\"AnomalyId\":\"" + UUID.randomUUID() + "\",\"Feedback\":\"YES\"}")
                .statusCode(400).body("message", containsString("invalid anomaly"));
    }
}
