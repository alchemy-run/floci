package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * JSON 1.0 PutCompositeAlarm / DescribeAlarms / DeleteAlarms used by Alchemy
 * {@code AWS.CloudWatch.CompositeAlarm}.
 */
@QuarkusTest
class CloudWatchCompositeAlarmIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "GraniteServiceVersion20100801.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/monitoring/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static void post(String action, String body) {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + action)
            .header("Authorization", AUTH)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void putDescribeListAndDeleteCompositeAlarm() {
        String metricName = "alchemy-test-composite-list-metric";
        String compositeName = "alchemy-test-composite-list";

        post("PutMetricAlarm", """
                {
                  "AlarmName": "%s",
                  "MetricName": "Errors",
                  "Namespace": "AWS/Lambda",
                  "Statistic": "Sum",
                  "Period": 60,
                  "EvaluationPeriods": 1,
                  "Threshold": 1,
                  "ComparisonOperator": "GreaterThanOrEqualToThreshold"
                }
                """.formatted(metricName));

        post("PutCompositeAlarm", """
                {
                  "AlarmName": "%s",
                  "AlarmRule": "ALARM(\\"%s\\")"
                }
                """.formatted(compositeName, metricName));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "DescribeAlarms")
            .header("Authorization", AUTH)
            .body("{\"AlarmTypes\":[\"CompositeAlarm\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CompositeAlarms.find { it.AlarmName == '" + compositeName + "' }.AlarmName",
                    equalTo(compositeName))
            .body("CompositeAlarms.find { it.AlarmName == '" + compositeName + "' }.AlarmRule",
                    equalTo("ALARM(\"" + metricName + "\")"));

        post("DeleteAlarms", """
                {"AlarmNames":["%s","%s"]}
                """.formatted(compositeName, metricName));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "DescribeAlarms")
            .header("Authorization", AUTH)
            .body("""
                    {
                      "AlarmNames":["%s","%s"],
                      "AlarmTypes":["CompositeAlarm","MetricAlarm"]
                    }
                    """.formatted(compositeName, metricName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CompositeAlarms", hasSize(0))
            .body("MetricAlarms", hasSize(0));
    }
}
