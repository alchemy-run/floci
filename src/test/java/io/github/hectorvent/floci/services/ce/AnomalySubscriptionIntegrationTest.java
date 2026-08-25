package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration tests for Cost Explorer anomaly subscriptions.
 * Protocol: JSON 1.1 — {@code X-Amz-Target: AWSInsightsIndexService.<Action>}
 */
@QuarkusTest
class AnomalySubscriptionIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ce/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getAnomalySubscriptions_unknownArn_returnsEmptyList() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalySubscriptions")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SubscriptionArnList\":[\"arn:aws:ce::000000000000:anomalysubscription/00000000-0000-0000-0000-000000000000\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalySubscriptions", hasSize(0));
    }

    @Test
    void deleteAnomalySubscription_unknownArn_returnsUnknownSubscriptionException() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteAnomalySubscription")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SubscriptionArn\":\"arn:aws:ce::000000000000:anomalysubscription/00000000-0000-0000-0000-000000000000\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("__type", equalTo("UnknownSubscriptionException"));
    }

    @Test
    void lifecycle_createUpdateDeleteSubscription() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String monitorName = "sub-mon-" + suffix;
        String subscriptionName = "sub-" + suffix;

        String monitorArn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalyMonitor")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AnomalyMonitor\":{" +
                    "\"MonitorName\":\"" + monitorName + "\"," +
                    "\"MonitorType\":\"CUSTOM\"," +
                    "\"MonitorSpecification\":{\"Tags\":{\"Key\":\"CostCenter\",\"Values\":[\"alchemy-test-sub\"]}}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("MonitorArn", startsWith("arn:aws:ce::"))
            .extract().path("MonitorArn");

        String subscriptionArn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalySubscription")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AnomalySubscription\":{" +
                    "\"SubscriptionName\":\"" + subscriptionName + "\"," +
                    "\"MonitorArnList\":[\"" + monitorArn + "\"]," +
                    "\"Frequency\":\"DAILY\"," +
                    "\"Subscribers\":[{\"Type\":\"EMAIL\",\"Address\":\"anomaly-test@example.com\"}]," +
                    "\"ThresholdExpression\":{\"Dimensions\":{\"Key\":\"ANOMALY_TOTAL_IMPACT_ABSOLUTE\"," +
                    "\"MatchOptions\":[\"GREATER_THAN_OR_EQUAL\"],\"Values\":[\"100\"]}}}," +
                    "\"ResourceTags\":[{\"Key\":\"fixture\",\"Value\":\"cost-explorer-anomaly-subscription\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SubscriptionArn", notNullValue())
            .extract().path("SubscriptionArn");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalySubscriptions")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SubscriptionArnList\":[\"" + subscriptionArn + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalySubscriptions", hasSize(1))
            .body("AnomalySubscriptions[0].SubscriptionName", equalTo(subscriptionName))
            .body("AnomalySubscriptions[0].Frequency", equalTo("DAILY"))
            .body("AnomalySubscriptions[0].MonitorArnList[0]", equalTo(monitorArn))
            .body("AnomalySubscriptions[0].Subscribers[0].Address", equalTo("anomaly-test@example.com"))
            .body("AnomalySubscriptions[0].Subscribers[0].Type", equalTo("EMAIL"))
            .body("AnomalySubscriptions[0].ThresholdExpression.Dimensions.Values[0]", equalTo("100"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.ListTagsForResource")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + subscriptionArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTags.find { it.Key == 'fixture' }.Value",
                    equalTo("cost-explorer-anomaly-subscription"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.UpdateAnomalySubscription")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SubscriptionArn\":\"" + subscriptionArn + "\"," +
                    "\"Frequency\":\"WEEKLY\"," +
                    "\"ThresholdExpression\":{\"Dimensions\":{\"Key\":\"ANOMALY_TOTAL_IMPACT_ABSOLUTE\"," +
                    "\"MatchOptions\":[\"GREATER_THAN_OR_EQUAL\"],\"Values\":[\"250\"]}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SubscriptionArn", equalTo(subscriptionArn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalySubscriptions")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SubscriptionArnList\":[\"" + subscriptionArn + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalySubscriptions[0].Frequency", equalTo("WEEKLY"))
            .body("AnomalySubscriptions[0].ThresholdExpression.Dimensions.Values[0]", equalTo("250"));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteAnomalySubscription")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SubscriptionArn\":\"" + subscriptionArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetAnomalySubscriptions")
            .header("Authorization", AUTH_HEADER)
            .body("{\"SubscriptionArnList\":[\"" + subscriptionArn + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AnomalySubscriptions", hasSize(0));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.DeleteAnomalyMonitor")
            .header("Authorization", AUTH_HEADER)
            .body("{\"MonitorArn\":\"" + monitorArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createAnomalySubscription_duplicateName_returnsValidationException() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String monitorArn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalyMonitor")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AnomalyMonitor\":{" +
                    "\"MonitorName\":\"dup-mon-" + suffix + "\"," +
                    "\"MonitorType\":\"CUSTOM\"," +
                    "\"MonitorSpecification\":{\"Tags\":{\"Key\":\"CostCenter\",\"Values\":[\"dup\"]}}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("MonitorArn");

        String body = "{\"AnomalySubscription\":{" +
                "\"SubscriptionName\":\"dup-sub-" + suffix + "\"," +
                "\"MonitorArnList\":[\"" + monitorArn + "\"]," +
                "\"Frequency\":\"DAILY\"," +
                "\"Subscribers\":[{\"Type\":\"EMAIL\",\"Address\":\"anomaly-test@example.com\"}]}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalySubscription")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.CreateAnomalySubscription")
            .header("Authorization", AUTH_HEADER)
            .body(body)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
}
