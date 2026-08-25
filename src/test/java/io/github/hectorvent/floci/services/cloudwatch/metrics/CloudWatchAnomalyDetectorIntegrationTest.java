package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudWatchAnomalyDetectorIntegrationTest {

    private static final String TARGET_PREFIX = "GraniteServiceVersion20100801.";
    private static final String NAMESPACE = "Alchemy/FlociAnomaly";
    private static final String METRIC = "Errors";
    private static final String DIM_VALUE = "alchemy-floci-anomaly-detector-it";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putDescribeDeleteSingleMetricAnomalyDetector() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "PutAnomalyDetector")
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .body("""
                        {
                          "Namespace": "%s",
                          "MetricName": "%s",
                          "Stat": "Sum",
                          "Dimensions": [
                            { "Name": "FunctionName", "Value": "%s" }
                          ]
                        }
                        """.formatted(NAMESPACE, METRIC, DIM_VALUE))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DescribeAnomalyDetectors")
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .body("""
                        {
                          "Namespace": "%s",
                          "MetricName": "%s",
                          "Dimensions": [
                            { "Name": "FunctionName", "Value": "%s" }
                          ],
                          "AnomalyDetectorTypes": ["SINGLE_METRIC"]
                        }
                        """.formatted(NAMESPACE, METRIC, DIM_VALUE))
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("AnomalyDetectors.size()", equalTo(1))
                .body("AnomalyDetectors[0].Namespace", equalTo(NAMESPACE))
                .body("AnomalyDetectors[0].MetricName", equalTo(METRIC))
                .body("AnomalyDetectors[0].Stat", equalTo("Sum"))
                .body("AnomalyDetectors[0].SingleMetricAnomalyDetector.Namespace", equalTo(NAMESPACE))
                .body("AnomalyDetectors[0].SingleMetricAnomalyDetector.Stat", equalTo("Sum"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DescribeAnomalyDetectors")
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("AnomalyDetectors.Namespace", hasItem(NAMESPACE))
                .body("AnomalyDetectors.MetricName", hasItem(METRIC));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteAnomalyDetector")
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .body("""
                        {
                          "Namespace": "%s",
                          "MetricName": "%s",
                          "Stat": "Sum",
                          "Dimensions": [
                            { "Name": "FunctionName", "Value": "%s" }
                          ]
                        }
                        """.formatted(NAMESPACE, METRIC, DIM_VALUE))
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DescribeAnomalyDetectors")
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("AnomalyDetectors.SingleMetricAnomalyDetector.Dimensions.Value.flatten()",
                        not(hasItem(DIM_VALUE)));
    }

    @Test
    void deleteMissingAnomalyDetectorReturnsResourceNotFoundException() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteAnomalyDetector")
                .contentType(CONTENT_TYPE_AWS_JSON_1_0)
                .body("""
                        {
                          "Namespace": "%s",
                          "MetricName": "DoesNotExist",
                          "Stat": "Sum"
                        }
                        """.formatted(NAMESPACE))
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
