package io.github.hectorvent.floci.services.timestream;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.core.common.AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * JSON 1.0 Timestream scheduled-query coverage used by Alchemy
 * ScheduledQuery.test.ts: create / describe / list / update (pause) / delete.
 */
@QuarkusTest
class TimestreamScheduledQueryIntegrationTest {

    private static final String CONTENT_TYPE = CONTENT_TYPE_AWS_JSON_1_0;
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/timestream/aws4_request";
    private static final String TARGET = "Timestream_20181101.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeEndpoints_returnsAccessDeniedForClosedLiveAnalytics() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeEndpoints")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", equalTo(TimestreamService.NOT_ONBOARDED_MESSAGE));
    }

    @Test
    void describeScheduledQuery_missing_returnsResourceNotFoundException() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeScheduledQuery")
                .header("Authorization", AUTH)
                .body("{\"ScheduledQueryArn\":\"arn:aws:timestream:us-east-1:000000000000:scheduled-query/missing-sq\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createDescribeUpdateDelete_roundTrip() {
        String name = "hourly-count-" + UUID.randomUUID().toString().substring(0, 8);
        String createBody = """
                {
                  "Name": "%s",
                  "QueryString": "SELECT COUNT(*) FROM \\"metrics\\".\\"cpu\\"",
                  "ScheduleConfiguration": {"ScheduleExpression": "rate(1 hour)"},
                  "NotificationConfiguration": {"SnsConfiguration": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:sq"}},
                  "ScheduledQueryExecutionRoleArn": "arn:aws:iam::000000000000:role/SqRole",
                  "ErrorReportConfiguration": {"S3Configuration": {"BucketName": "sq-errors"}},
                  "Tags": [{"Key": "Environment", "Value": "test"}]
                }
                """.formatted(name);

        String arn = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateScheduledQuery")
                .header("Authorization", AUTH)
                .body(createBody)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Arn", notNullValue())
        .extract()
                .path("Arn");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeScheduledQuery")
                .header("Authorization", AUTH)
                .body("{\"ScheduledQueryArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ScheduledQuery.Name", equalTo(name))
                .body("ScheduledQuery.State", equalTo("ENABLED"))
                .body("ScheduledQuery.ScheduleConfiguration.ScheduleExpression", equalTo("rate(1 hour)"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListScheduledQueries")
                .header("Authorization", AUTH)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ScheduledQueries.find { it.Name == '" + name + "' }.State", equalTo("ENABLED"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListTagsForResource")
                .header("Authorization", AUTH)
                .body("{\"ResourceARN\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags[0].Key", equalTo("Environment"))
                .body("Tags[0].Value", equalTo("test"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "UpdateScheduledQuery")
                .header("Authorization", AUTH)
                .body("{\"ScheduledQueryArn\":\"" + arn + "\",\"State\":\"DISABLED\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeScheduledQuery")
                .header("Authorization", AUTH)
                .body("{\"ScheduledQueryArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ScheduledQuery.State", equalTo("DISABLED"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeleteScheduledQuery")
                .header("Authorization", AUTH)
                .body("{\"ScheduledQueryArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribeScheduledQuery")
                .header("Authorization", AUTH)
                .body("{\"ScheduledQueryArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createScheduledQuery_duplicate_returnsConflictException() {
        String name = "dup-sq-" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {
                  "Name": "%s",
                  "QueryString": "SELECT COUNT(*) FROM \\"metrics\\".\\"cpu\\"",
                  "ScheduleConfiguration": {"ScheduleExpression": "rate(1 hour)"},
                  "NotificationConfiguration": {"SnsConfiguration": {"TopicArn": "arn:aws:sns:us-east-1:000000000000:sq"}},
                  "ScheduledQueryExecutionRoleArn": "arn:aws:iam::000000000000:role/SqRole",
                  "ErrorReportConfiguration": {"S3Configuration": {"BucketName": "sq-errors"}}
                }
                """.formatted(name);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateScheduledQuery")
                .header("Authorization", AUTH)
                .body(body)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreateScheduledQuery")
                .header("Authorization", AUTH)
                .body(body)
        .when()
                .post("/")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
    }
}
