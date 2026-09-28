package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CloudTrailInsightsIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260921/us-east-1/cloudtrail/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getTrailAndInsightSelectorsRoundTripWithoutChangingOtherSettings() {
        String name = "insight-selector-lifecycle";
        String arn = call("CreateTrail", Map.of("Name", name, "S3BucketName", "insight-selector-logs"))
                .statusCode(200).extract().path("TrailARN");
        try {
            call("GetTrail", Map.of("Name", arn)).statusCode(200)
                    .body("Trail.Name", equalTo(name))
                    .body("Trail.HomeRegion", equalTo("us-east-1"))
                    .body("Trail.HasInsightSelectors", equalTo(false));
            call("GetInsightSelectors", Map.of("TrailName", name)).statusCode(400)
                    .body("__type", containsString("InsightNotEnabledException"));
            call("PutInsightSelectors", Map.of("TrailName", arn, "InsightSelectors",
                    List.of(Map.of("InsightType", "ApiCallRateInsight"))))
                    .statusCode(200).body("TrailARN", equalTo(arn))
                    .body("InsightSelectors.InsightType", contains("ApiCallRateInsight"));
            call("UpdateTrail", Map.of("Name", name, "S3KeyPrefix", "audit"))
                    .statusCode(200);
            call("GetTrail", Map.of("Name", name)).statusCode(200)
                    .body("Trail.S3KeyPrefix", equalTo("audit"))
                    .body("Trail.HasInsightSelectors", equalTo(true));
            call("GetInsightSelectors", Map.of("TrailName", name)).statusCode(200)
                    .body("InsightSelectors.InsightType", contains("ApiCallRateInsight"));
            call("PutInsightSelectors", Map.of("TrailName", arn, "InsightSelectors",
                    List.of(Map.of("InsightType", "Unsupported"))))
                    .statusCode(400).body("__type", containsString("InvalidInsightSelectorsException"));
            call("GetInsightSelectors", Map.of("TrailName", arn)).statusCode(200)
                    .body("InsightSelectors.InsightType", contains("ApiCallRateInsight"));
            given().header("Authorization",
                            "AWS4-HMAC-SHA256 Credential=222222222222/20260921/us-east-1/cloudtrail/aws4_request")
                    .header("X-Amz-Target", "CloudTrail_20131101.GetTrail")
                    .contentType("application/x-amz-json-1.1").body(Map.of("Name", arn))
                    .post("/").then().statusCode(400)
                    .body("__type", containsString("TrailNotFoundException"));
            call("PutInsightSelectors", Map.of("TrailName", name, "InsightSelectors", List.of()))
                    .statusCode(200).body("InsightSelectors", empty());
            call("GetTrail", Map.of("Name", arn)).statusCode(200)
                    .body("Trail.HasInsightSelectors", equalTo(false));
            call("GetInsightSelectors", Map.of("TrailName", arn)).statusCode(400)
                    .body("__type", containsString("InsightNotEnabledException"));
        } finally {
            call("DeleteTrail", Map.of("Name", arn)).statusCode(200);
        }
        call("GetTrail", Map.of("Name", name)).statusCode(400)
                .body("__type", containsString("TrailNotFoundException"));
        call("GetInsightSelectors", Map.of("TrailName", arn)).statusCode(400)
                .body("__type", containsString("TrailNotFoundException"));
    }

    private static ValidatableResponse call(String operation, Map<String, Object> body) {
        return given().header("Authorization", AUTH)
                .header("X-Amz-Target", "CloudTrail_20131101." + operation)
                .contentType("application/x-amz-json-1.1").body(body)
                .post("/").then();
    }
}
