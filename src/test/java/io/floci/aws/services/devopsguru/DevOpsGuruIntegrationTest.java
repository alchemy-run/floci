package io.floci.aws.services.devopsguru;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Amazon DevOps Guru over REST: the resource collection, integrations and notification channels, and insights its
 * analysis opens from CloudWatch alarms on monitored resources, notifies through SNS and closes.
 */
@QuarkusTest
class DevOpsGuruIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/" + service + "/aws4_request";
    }

    private static RequestSpecification guru() {
        return given().header("Authorization", auth("devops-guru")).contentType("application/json");
    }

    private static RequestSpecification query(String service) {
        return given().header("Authorization", auth(service)).contentType("application/x-www-form-urlencoded");
    }

    private static ValidatableResponse post(String path, String body) {
        return guru().body(body).post(path).then();
    }

    private static ValidatableResponse put(String path, String body) {
        return guru().body(body).put(path).then();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String topic(String name) {
        return query("sns").formParam("Action", "CreateTopic").formParam("Name", name).post("/").then()
                .statusCode(200).extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    @Test
    void collectionIntegrationsAndChannels() {
        put("/resource-collections", "{\"Action\":\"ADD\",\"ResourceCollection\":{\"Tags\":[{\"AppBoundaryKey\":"
                + "\"team\",\"TagValues\":[\"a\"]}]}}").statusCode(400).body("message", containsString("devops-guru-"));

        put("/service-integrations", "{\"ServiceIntegration\":{\"OpsCenter\":{\"OptInStatus\":\"ENABLED\"},"
                + "\"KMSServerSideEncryption\":{\"Type\":\"CUSTOMER_MANAGED_KEY\",\"KMSKeyId\":\"missing\"}}}")
                .statusCode(400).body("message", containsString("KMS key"));
        put("/service-integrations", "{\"ServiceIntegration\":{\"OpsCenter\":{\"OptInStatus\":\"ENABLED\"}}}")
                .statusCode(200);
        guru().get("/service-integrations").then().statusCode(200)
                .body("ServiceIntegration.OpsCenter.OptInStatus", equalTo("ENABLED"))
                .body("ServiceIntegration.KMSServerSideEncryption.Type", equalTo("AWS_OWNED_KMS_KEY"));
        put("/service-integrations", "{\"ServiceIntegration\":{\"OpsCenter\":{\"OptInStatus\":\"DISABLED\"}}}")
                .statusCode(200);

        put("/event-sources", "{\"EventSources\":{\"AmazonCodeGuruProfiler\":{\"Status\":\"ENABLED\"}}}")
                .statusCode(200);
        post("/event-sources", "{}").statusCode(200).body("EventSources.AmazonCodeGuruProfiler.Status",
                equalTo("ENABLED"));
        put("/event-sources", "{\"EventSources\":{\"AmazonCodeGuruProfiler\":{\"Status\":\"DISABLED\"}}}")
                .statusCode(200);

        put("/channels", "{\"Config\":{\"Sns\":{\"TopicArn\":\"arn:aws:sns:us-east-1:" + ACCOUNT
                + ":missing-topic\"}}}").statusCode(404);
        String topic = topic("guru-" + suffix());
        String channel = put("/channels", "{\"Config\":{\"Sns\":{\"TopicArn\":\"" + topic + "\"},\"Filters\":"
                + "{\"Severities\":[\"HIGH\"]}}}").statusCode(200).body("Id", notNullValue()).extract().path("Id");
        put("/channels", "{\"Config\":{\"Sns\":{\"TopicArn\":\"" + topic + "\"}}}").statusCode(409);
        post("/channels", "{}").statusCode(200).body("Channels.Id", hasItem(channel));
        guru().delete("/channels/" + channel).then().statusCode(200);
        guru().delete("/channels/" + channel).then().statusCode(200);
    }

    @Test
    void anAlarmOnAMonitoredResourceOpensAndClosesAnInsight() {
        String value = "app-" + suffix();
        String queue = "guru-" + suffix();
        query("sqs").formParam("Action", "CreateQueue").formParam("QueueName", queue)
                .formParam("Tag.1.Key", "devops-guru-app").formParam("Tag.1.Value", value).post("/").then()
                .statusCode(200);
        put("/resource-collections", "{\"Action\":\"ADD\",\"ResourceCollection\":{\"Tags\":[{\"AppBoundaryKey\":"
                + "\"devops-guru-app\",\"TagValues\":[\"" + value + "\"]}]}}").statusCode(200);
        guru().get("/resource-collections/AWS_TAGS").then().statusCode(200)
                .body("ResourceCollection.Tags[0].TagValues", hasItem(value));
        post("/monitoredResources", "{\"Filters\":{\"ResourcePermission\":\"FULL_PERMISSION\","
                + "\"ResourceTypeFilters\":[\"SQS_QUEUE\"]}}").statusCode(200)
                .body("MonitoredResourceIdentifiers.MonitoredResourceName", hasItem(queue));

        // The channel's topic delivers to a queue, so the notification can be read back.
        String topic = topic("guru-notify-" + suffix());
        String inbox = "guru-inbox-" + suffix();
        String inboxUrl = query("sqs").formParam("Action", "CreateQueue").formParam("QueueName", inbox).post("/")
                .then().statusCode(200).extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        query("sns").formParam("Action", "Subscribe").formParam("TopicArn", topic).formParam("Protocol", "sqs")
                .formParam("Endpoint", "arn:aws:sqs:us-east-1:" + ACCOUNT + ":" + inbox).post("/").then()
                .statusCode(200);
        String channel = put("/channels", "{\"Config\":{\"Sns\":{\"TopicArn\":\"" + topic + "\"},\"Filters\":"
                + "{\"MessageTypes\":[\"NEW_INSIGHT\",\"CLOSED_INSIGHT\"]}}}").statusCode(200).extract().path("Id");

        String alarm = "guru-age-" + suffix();
        query("monitoring").formParam("Action", "PutMetricAlarm").formParam("AlarmName", alarm)
                .formParam("Namespace", "AWS/SQS").formParam("MetricName", "ApproximateAgeOfOldestMessage")
                .formParam("Dimensions.member.1.Name", "QueueName").formParam("Dimensions.member.1.Value", queue)
                .formParam("Statistic", "Maximum").formParam("Period", "60").formParam("EvaluationPeriods", "1")
                .formParam("Threshold", "300").formParam("ComparisonOperator", "GreaterThanThreshold")
                .post("/").then().statusCode(200);
        query("monitoring").formParam("Action", "SetAlarmState").formParam("AlarmName", alarm)
                .formParam("StateValue", "ALARM").formParam("StateReason", "Messages are aging").post("/").then()
                .statusCode(200);

        ValidatableResponse ongoing = post("/insights", "{\"StatusFilter\":{\"Ongoing\":{\"Type\":\"REACTIVE\"}}}")
                .statusCode(200).body("ReactiveInsights", hasSize(1))
                .body("ReactiveInsights[0].Severity", equalTo("MEDIUM"))
                .body("ReactiveInsights[0].ServiceCollection.ServiceNames", hasItem("SQS"));
        String insight = ongoing.extract().path("ReactiveInsights[0].Id");
        guru().get("/accounts/health").then().statusCode(200).body("OpenReactiveInsights", equalTo(1))
                .body("AnalyzedResourceCount", greaterThanOrEqualTo(1));
        post("/anomalies/insight/" + insight, "{}").statusCode(200).body("ReactiveAnomalies", hasSize(1))
                .body("ReactiveAnomalies[0].SourceDetails.CloudWatchMetrics[0].MetricName",
                        equalTo("ApproximateAgeOfOldestMessage"));
        post("/recommendations", "{\"InsightId\":\"" + insight + "\"}").statusCode(200)
                .body("Recommendations[0].Name", equalTo("Scale the queue's consumers"));
        guru().delete("/insights/" + insight).then().statusCode(409);
        guru().get("/accounts/health/resource-collection/AWS_TAGS").then().statusCode(200)
                .body("Tags[0].Insight.OpenReactiveInsights", equalTo(1));
        query("sqs").formParam("Action", "ReceiveMessage").formParam("QueueUrl", inboxUrl).post("/").then()
                .statusCode(200).body(containsString("NEW_INSIGHT"));

        query("monitoring").formParam("Action", "SetAlarmState").formParam("AlarmName", alarm)
                .formParam("StateValue", "OK").formParam("StateReason", "Drained").post("/").then().statusCode(200);
        guru().get("/insights/" + insight).then().statusCode(200).body("ReactiveInsight.Status", equalTo("CLOSED"))
                .body("ReactiveInsight.InsightTimeRange.EndTime", notNullValue());
        put("/feedback", "{\"InsightFeedback\":{\"Id\":\"" + insight + "\",\"Feedback\":\"VALID_COLLECTION\"}}")
                .statusCode(200);
        post("/feedback", "{\"InsightId\":\"" + insight + "\"}").statusCode(200)
                .body("InsightFeedback.Feedback", equalTo("VALID_COLLECTION"));
        long now = Instant.now().getEpochSecond();
        post("/accounts/overview", "{\"FromTime\":" + (now - 3600) + "}").statusCode(200)
                .body("ReactiveInsights", greaterThanOrEqualTo(1));
        guru().delete("/insights/" + insight).then().statusCode(200);

        put("/cost-estimation", "{\"ResourceCollection\":{\"Tags\":[{\"AppBoundaryKey\":\"devops-guru-app\","
                + "\"TagValues\":[\"" + value + "\"]}]}}").statusCode(200);
        guru().get("/cost-estimation").then().statusCode(200).body("Status", equalTo("COMPLETED"))
                .body("Costs[0].Type", equalTo("AWS::SQS::Queue")).body("TotalCost", notNullValue());

        guru().delete("/channels/" + channel).then().statusCode(200);
        put("/resource-collections", "{\"Action\":\"REMOVE\",\"ResourceCollection\":{\"Tags\":[{\"AppBoundaryKey\":"
                + "\"devops-guru-app\",\"TagValues\":[\"" + value + "\"]}]}}").statusCode(200);
        guru().get("/resource-collections/AWS_TAGS").then().statusCode(200)
                .body("ResourceCollection.Tags", hasSize(0));
    }
}
