package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PutBucketTagging recorded by a logging trail is published to the default
 * EventBridge bus as {@code AWS API Call via CloudTrail} and delivered to
 * matching rules — the Alchemy {@code consumeApiCallEvents} path.
 */
@QuarkusTest
class CloudTrailEventBridgeIntegrationTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";
    private static final String SQS_JSON = "application/x-amz-json-1.0";
    private static final String EVENTS_TARGET = "AWSEvents.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putBucketTaggingIsDeliveredToEventBridgeRule() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String trailName = "eb-ct-" + suffix;
        String destBucket = "eb-ct-logs-" + suffix;
        String sourceBucket = "eb-ct-src-" + suffix;
        String queueName = "eb-ct-sink-" + suffix;
        String ruleName = "eb-ct-rule-" + suffix;

        createBucket(destBucket);
        createBucket(sourceBucket);

        invokeCloudTrail("CreateTrail", String.format("""
                {"Name":"%s","S3BucketName":"%s"}
                """, trailName, destBucket))
            .then().statusCode(200);
        invokeCloudTrail("StartLogging", String.format("{\"Name\":\"%s\"}", trailName))
            .then().statusCode(200);

        String queueUrl = given()
            .contentType(SQS_JSON)
            .header("X-Amz-Target", "AmazonSQS.CreateQueue")
            .body("{\"QueueName\":\"" + queueName + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .extract().jsonPath().getString("QueueUrl");

        String queueArn = given()
            .contentType(SQS_JSON)
            .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
            .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
            .when().post("/")
            .then().statusCode(200)
            .extract().jsonPath().getString("Attributes.QueueArn");

        // Same pattern Alchemy.CloudTrail.consumeApiCallEvents uses.
        String eventPattern = """
                {"detail-type":["AWS API Call via CloudTrail"],\
                "detail":{"eventSource":["s3.amazonaws.com"],"eventName":["PutBucketTagging"]}}
                """.replace("\n", "");

        given()
            .contentType(JSON11)
            .header("X-Amz-Target", EVENTS_TARGET + "PutRule")
            .body(String.format(
                    "{\"Name\":\"%s\",\"EventPattern\":\"%s\"}",
                    ruleName, eventPattern.replace("\"", "\\\"")))
            .when().post("/")
            .then().statusCode(200);

        given()
            .contentType(JSON11)
            .header("X-Amz-Target", EVENTS_TARGET + "PutTargets")
            .body(String.format(
                    "{\"Rule\":\"%s\",\"Targets\":[{\"Id\":\"1\",\"Arn\":\"%s\"}]}",
                    ruleName, queueArn))
            .when().post("/")
            .then().statusCode(200);

        // Distilled-style virtual-hosted PutBucketTagging: Host={bucket} and
        // Path=/{bucket}?tagging (Smithy uri kept on the vhost endpoint).
        given()
            .header("Host", sourceBucket + ".localhost")
            .contentType("application/xml")
            .body("<Tagging><TagSet><Tag><Key>cloudtrail-probe</Key><Value>1</Value></Tag></TagSet></Tagging>")
            .when().put("/" + sourceBucket + "?tagging")
            .then().statusCode(204);

        String body = given()
            .contentType(SQS_JSON)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + queueUrl
                    + "\",\"MaxNumberOfMessages\":1,\"WaitTimeSeconds\":2}")
            .when().post("/")
            .then().statusCode(200)
            .body("Messages", hasSize(1))
            .extract().jsonPath().getString("Messages[0].Body");

        assertTrue(body.contains("\"detail-type\":\"AWS API Call via CloudTrail\""), body);
        assertTrue(body.contains("\"eventName\":\"PutBucketTagging\""), body);
        assertTrue(body.contains("\"eventSource\":\"s3.amazonaws.com\""), body);
        assertTrue(body.contains("\"bucketName\":\"" + sourceBucket + "\""), body);
    }

    @Test
    void distilledStyleGetBucketTaggingOnExistingBucketIsNoSuchTagSetNotNoSuchBucket() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String bucket = "eb-ct-tag-" + suffix;
        createBucket(bucket);

        given()
            .header("Host", bucket + ".localhost")
        .when().get("/" + bucket + "?tagging")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchTagSet"));
    }

    private static io.restassured.response.Response invokeCloudTrail(String action, String body) {
        return given()
            .header("X-Amz-Target", CT_TARGET + action)
            .contentType(JSON11)
            .body(body)
        .when().post("/");
    }

    private static void createBucket(String name) {
        given().when().put("/" + name).then().statusCode(200);
    }
}
