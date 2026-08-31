package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

/**
 * Binding-surface operations used by Alchemy CloudWatch Bindings.test.ts
 * (JSON 1.0 / GraniteServiceVersion20100801).
 */
@QuarkusTest
class CloudWatchBindingsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "GraniteServiceVersion20100801";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/monitoring/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getDashboardMissingIsDashboardNotFoundError() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".GetDashboard")
                .header("Authorization", AUTH)
                .body("{\"DashboardName\":\"floci-bindings-missing-dashboard\"}")
                .when()
                .post("/")
                .then()
                .statusCode(404)
                .body("__type", equalTo("DashboardNotFoundError"));
    }

    @Test
    void putGetListDashboardAndWidgetImageAndAlarmActions() {
        String dashboardName = "floci-bindings-dashboard";
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".PutDashboard")
                .header("Authorization", AUTH)
                .body("{\"DashboardName\":\"" + dashboardName
                        + "\",\"DashboardBody\":\"{\\\"widgets\\\":[]}\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".GetDashboard")
                .header("Authorization", AUTH)
                .body("{\"DashboardName\":\"" + dashboardName + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("DashboardName", equalTo(dashboardName))
                .body("DashboardBody", equalTo("{\"widgets\":[]}"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".ListDashboards")
                .header("Authorization", AUTH)
                .body("{}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("DashboardEntries.DashboardName", hasItem(dashboardName));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".GetMetricWidgetImage")
                .header("Authorization", AUTH)
                .body("{\"MetricWidget\":\"{\\\"metrics\\\":[[\\\"NS\\\",\\\"M\\\"]]}\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("MetricWidgetImage.size()", greaterThan(0));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".PutMetricAlarm")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "AlarmName": "floci-bindings-alarm",
                          "MetricName": "BindingTestMetric",
                          "Namespace": "Alchemy/CloudWatchBindings",
                          "Statistic": "Sum",
                          "Period": 60,
                          "EvaluationPeriods": 1,
                          "Threshold": 1,
                          "ComparisonOperator": "GreaterThanOrEqualToThreshold"
                        }
                        """)
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".DescribeAlarmsForMetric")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "Namespace": "Alchemy/CloudWatchBindings",
                          "MetricName": "BindingTestMetric",
                          "Statistic": "Sum",
                          "Period": 60
                        }
                        """)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("MetricAlarms.AlarmName", hasItem("floci-bindings-alarm"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".DisableAlarmActions")
                .header("Authorization", AUTH)
                .body("{\"AlarmNames\":[\"floci-bindings-alarm\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".EnableAlarmActions")
                .header("Authorization", AUTH)
                .body("{\"AlarmNames\":[\"floci-bindings-alarm\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".DescribeAlarmHistory")
                .header("Authorization", AUTH)
                .body("{\"AlarmName\":\"floci-bindings-alarm\",\"MaxRecords\":10}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("AlarmHistoryItems", hasSize(greaterThan(0)));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".DescribeAlarmContributors")
                .header("Authorization", AUTH)
                .body("{\"AlarmName\":\"floci-bindings-alarm\"}")
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".ListManagedInsightRules")
                .header("Authorization", AUTH)
                .body("{\"ResourceARN\":\"arn:aws:cloudwatch:us-east-1:000000000000:alarm:floci-bindings-alarm\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("ManagedRules", hasSize(0));
    }

    @Test
    void insightRuleReportAndToggle() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".PutInsightRule")
                .header("Authorization", AUTH)
                .body("""
                        {
                          "RuleName": "floci-bindings-rule",
                          "RuleState": "ENABLED",
                          "RuleDefinition": "{\\"Schema\\":{\\"Name\\":\\"CloudWatchLogRule\\",\\"Version\\":1},\\"AggregateOn\\":\\"Count\\"}"
                        }
                        """)
                .when()
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".GetInsightRuleReport")
                .header("Authorization", AUTH)
                .body("{\"RuleName\":\"floci-bindings-rule\",\"Period\":300}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Contributors", hasSize(0));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".DisableInsightRules")
                .header("Authorization", AUTH)
                .body("{\"RuleNames\":[\"floci-bindings-rule\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Failures", hasSize(0));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + ".EnableInsightRules")
                .header("Authorization", AUTH)
                .body("{\"RuleNames\":[\"floci-bindings-rule\"]}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Failures", hasSize(0));
    }
}
