package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudTrailManagementIntegrationTest {
    private static final String ACCOUNT = "710000000001";
    private static final String OTHER = "710000000002";
    private static final String REGION = "us-east-1";

    @Inject ObjectMapper mapper;
    @Inject CloudTrailLogWriter writer;
    @Inject CloudTrailService trails;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void actualManagementCallsPopulateHistoryWithoutATrailAndRespectQueryScope() throws Exception {
        String parameter = "/cloudtrail/history";
        try {
            putParameter(ACCOUNT, REGION, parameter, "do-not-log-this-value");
            putParameter(ACCOUNT, REGION, parameter, "another-secret");
            var query = Map.<String, Object>of("LookupAttributes",
                    List.of(Map.of("AttributeKey", "EventName", "AttributeValue", "PutParameter")), "MaxResults", 1);
            var first = ct(ACCOUNT, REGION, "LookupEvents", query).statusCode(200)
                    .body("Events", hasSize(1)).body("NextToken", notNullValue()).extract();
            String firstId = first.path("Events[0].EventId");
            JsonNode event = mapper.readTree((String) first.path("Events[0].CloudTrailEvent"));
            assertEquals("ssm.amazonaws.com", event.path("eventSource").asText());
            assertEquals(parameter, event.path("requestParameters").path("name").asText());
            assertEquals(ACCOUNT, event.path("recipientAccountId").asText());
            assertFalse(event.path("readOnly").asBoolean());
            assertFalse(event.toString().contains("secret"));
            assertFalse(event.toString().contains("do-not-log"));
            String token = first.path("NextToken");
            var next = new java.util.HashMap<>(query);
            next.put("NextToken", token);
            ct(ACCOUNT, REGION, "LookupEvents", next).statusCode(200)
                    .body("Events", hasSize(1)).body("Events[0].EventId", not(equalTo(firstId)));
            ct(OTHER, REGION, "LookupEvents", next).statusCode(400)
                    .body("__type", containsString("InvalidNextTokenException"));
            ct(ACCOUNT, "us-west-2", "LookupEvents", next).statusCode(400)
                    .body("__type", containsString("InvalidNextTokenException"));
            var byId = Map.<String, Object>of("LookupAttributes",
                    List.of(Map.of("AttributeKey", "EventId", "AttributeValue", firstId)));
            ct(OTHER, REGION, "LookupEvents", byId).statusCode(200).body("Events", empty());
            ct(ACCOUNT, "us-west-2", "LookupEvents", byId).statusCode(200).body("Events", empty());
            ct(ACCOUNT, REGION, "LookupEvents", Map.of("StartTime", 0, "EndTime", 1)).statusCode(200)
                    .body("Events", empty());
            ct(ACCOUNT, REGION, "LookupEvents", Map.of("MaxResults", 51)).statusCode(400)
                    .body("__type", containsString("InvalidMaxResultsException"));
            ct(ACCOUNT, REGION, "LookupEvents", Map.of("StartTime", 2, "EndTime", 1)).statusCode(400)
                    .body("__type", containsString("InvalidTimeRangeException"));
            ct(ACCOUNT, REGION, "LookupEvents", Map.of("LookupAttributes", List.of(
                    Map.of("AttributeKey", "NotAnAttribute", "AttributeValue", "x")))).statusCode(400)
                    .body("__type", containsString("InvalidLookupAttributesException"));
            ct(ACCOUNT, REGION, "LookupEvents", Map.of("NextToken", "garbage")).statusCode(400)
                    .body("__type", containsString("InvalidNextTokenException"));
            call(ACCOUNT, REGION, "ssm", "AmazonSSM.GetParameter", Map.of("Name", "/cloudtrail/absent"))
                    .statusCode(400);
            String failed = ct(ACCOUNT, REGION, "LookupEvents", Map.of("LookupAttributes",
                    List.of(Map.of("AttributeKey", "EventName", "AttributeValue", "GetParameter")), "MaxResults", 1))
                    .statusCode(200).extract().path("Events[0].CloudTrailEvent");
            assertEquals("ParameterNotFound", mapper.readTree(failed).path("errorCode").asText());
        } finally {
            call(ACCOUNT, REGION, "ssm", "AmazonSSM.DeleteParameter", Map.of("Name", parameter)).statusCode(200);
        }
    }

    @Test
    void realSsmAndS3MutationsReachEventBridgeOnlyForMatchingLoggingTrails() throws Exception {
        String trail = "management-delivery";
        String logs = "management-delivery-logs";
        String source = "management-delivery-source";
        String rule = "management-delivery-rule";
        String parameter = "/cloudtrail/delivery";
        String queue = call(ACCOUNT, REGION, "sqs", "AmazonSQS.CreateQueue", Map.of("QueueName", rule))
                .statusCode(200).extract().path("QueueUrl");
        String queueArn = call(ACCOUNT, REGION, "sqs", "AmazonSQS.GetQueueAttributes",
                Map.of("QueueUrl", queue, "AttributeNames", List.of("QueueArn")))
                .statusCode(200).extract().path("Attributes.QueueArn");
        given().header("Authorization", auth(ACCOUNT, REGION, "s3")).put("/" + logs).then().statusCode(200);
        given().header("Authorization", auth(ACCOUNT, REGION, "s3")).put("/" + source).then().statusCode(200);
        ct(ACCOUNT, REGION, "CreateTrail", Map.of("Name", trail, "S3BucketName", logs)).statusCode(200);
        call(ACCOUNT, REGION, "events", "AWSEvents.PutRule", Map.of("Name", rule, "EventPattern",
                "{\"detail-type\":[\"AWS API Call via CloudTrail\"],\"detail\":{\"eventName\":[\"PutParameter\",\"GetParameter\",\"PutBucketTagging\"]}}"))
                .statusCode(200);
        call(ACCOUNT, REGION, "events", "AWSEvents.PutTargets",
                Map.of("Rule", rule, "Targets", List.of(Map.of("Id", "queue", "Arn", queueArn))))
                .statusCode(200).body("FailedEntryCount", equalTo(0));
        try {
            putParameter(ACCOUNT, REGION, parameter, "before-start");
            assertQueueEmpty(queue);
            ct(ACCOUNT, REGION, "StartLogging", Map.of("Name", trail)).statusCode(200);
            putParameter(ACCOUNT, REGION, parameter, "after-start");
            String message = receive(queue).statusCode(200).body("Messages", hasSize(1))
                    .extract().path("Messages[0].Body");
            JsonNode envelope = mapper.readTree(message);
            assertEquals("aws.ssm", envelope.path("source").asText());
            assertEquals(ACCOUNT, envelope.path("account").asText());
            assertEquals(REGION, envelope.path("region").asText());
            assertEquals("PutParameter", envelope.path("detail").path("eventName").asText());
            assertEquals(parameter, envelope.path("detail").path("requestParameters").path("name").asText());
            String eventId = envelope.path("detail").path("eventID").asText();
            ct(ACCOUNT, REGION, "LookupEvents", Map.of("LookupAttributes",
                    List.of(Map.of("AttributeKey", "EventId", "AttributeValue", eventId))))
                    .statusCode(200).body("Events[0].EventId", equalTo(eventId));
            call(ACCOUNT, REGION, "ssm", "AmazonSSM.GetParameter", Map.of("Name", parameter)).statusCode(200);
            assertQueueEmpty(queue);
            putParameter(OTHER, REGION, parameter, "other-account");
            putParameter(ACCOUNT, "us-west-2", parameter, "other-region");
            assertQueueEmpty(queue);
            given().header("Authorization", auth(ACCOUNT, REGION, "s3"))
                    .contentType("application/xml").body("<Tagging><TagSet><Tag><Key>audit</Key><Value>1</Value></Tag></TagSet></Tagging>")
                    .put("/" + source + "?tagging").then().statusCode(204);
            String s3 = receive(queue).statusCode(200).body("Messages", hasSize(1)).extract().path("Messages[0].Body");
            assertEquals(source, mapper.readTree(s3).path("detail").path("requestParameters").path("bucketName").asText());
            ct(ACCOUNT, REGION, "PutEventSelectors", Map.of("TrailName", trail, "EventSelectors",
                    List.of(Map.of("IncludeManagementEvents", false)))).statusCode(200);
            putParameter(ACCOUNT, REGION, parameter, "not-selected");
            assertQueueEmpty(queue);
            ct(ACCOUNT, REGION, "PutEventSelectors", Map.of("TrailName", trail, "AdvancedEventSelectors",
                    List.of(Map.of("FieldSelectors", List.of(
                            Map.of("Field", "eventCategory", "Equals", List.of("Management")),
                            Map.of("Field", "eventSource", "Equals", List.of("s3.amazonaws.com")))))))
                    .statusCode(200);
            putParameter(ACCOUNT, REGION, parameter, "advanced-not-selected");
            assertQueueEmpty(queue);
            ct(ACCOUNT, REGION, "StopLogging", Map.of("Name", trail)).statusCode(200);
            putParameter(ACCOUNT, REGION, parameter, "after-stop");
            assertQueueEmpty(queue);
            writer.flushNow();
            given().header("Authorization", auth(ACCOUNT, REGION, "s3")).get("/" + logs + "?list-type=2")
                    .then().statusCode(200).body(containsString("AWSLogs/" + ACCOUNT + "/CloudTrail/" + REGION));
        } finally {
            ct(ACCOUNT, REGION, "DeleteTrail", Map.of("Name", trail)).statusCode(200);
            call(ACCOUNT, REGION, "events", "AWSEvents.RemoveTargets", Map.of("Rule", rule, "Ids", List.of("queue"))).statusCode(200);
            call(ACCOUNT, REGION, "events", "AWSEvents.DeleteRule", Map.of("Name", rule)).statusCode(200);
            call(ACCOUNT, REGION, "sqs", "AmazonSQS.DeleteQueue", Map.of("QueueUrl", queue)).statusCode(200);
            call(ACCOUNT, REGION, "ssm", "AmazonSSM.DeleteParameter", Map.of("Name", parameter)).statusCode(200);
            call(OTHER, REGION, "ssm", "AmazonSSM.DeleteParameter", Map.of("Name", parameter)).statusCode(200);
            call(ACCOUNT, "us-west-2", "ssm", "AmazonSSM.DeleteParameter", Map.of("Name", parameter)).statusCode(200);
            deleteBucket(logs);
            deleteBucket(source);
        }
    }

    @Test
    void sameNamedTrailQueuesRemainIsolatedDuringDeletion() {
        String name = "management-queue-isolation";
        for (String account : List.of(ACCOUNT, OTHER)) {
            ct(account, REGION, "CreateTrail", Map.of("Name", name, "S3BucketName", "missing-isolation-destination"))
                    .statusCode(200);
            ct(account, REGION, "StartLogging", Map.of("Name", name)).statusCode(200);
        }
        ct(ACCOUNT, "us-west-2", "CreateTrail", Map.of("Name", name, "S3BucketName", "missing-isolation-destination"))
                .statusCode(200);
        ct(ACCOUNT, "us-west-2", "StartLogging", Map.of("Name", name)).statusCode(200);
        try {
            ct(ACCOUNT, REGION, "DeleteTrail", Map.of("Name", name)).statusCode(200);
            assertEquals(0, trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION, ACCOUNT)));
            assertTrue(trails.pendingRecordCount(new CloudTrailService.TrailKey(REGION, name, REGION, OTHER)) > 0);
            assertTrue(trails.pendingRecordCount(new CloudTrailService.TrailKey("us-west-2", name, "us-west-2", ACCOUNT)) > 0);
        } finally {
            ct(OTHER, REGION, "DeleteTrail", Map.of("Name", name)).statusCode(200);
            ct(ACCOUNT, "us-west-2", "DeleteTrail", Map.of("Name", name)).statusCode(200);
        }
    }

    @Test
    void publicKeysAreEmptyRatherThanFabricatedAndValidateRanges() {
        ct(ACCOUNT, REGION, "ListPublicKeys", Map.of()).statusCode(200).body("PublicKeyList", empty());
        ct(ACCOUNT, REGION, "ListPublicKeys", Map.of("StartTime", 10, "EndTime", 9)).statusCode(400)
                .body("__type", containsString("InvalidTimeRangeException"));
        ct(ACCOUNT, REGION, "ListPublicKeys", Map.of("NextToken", "fake")).statusCode(400)
                .body("__type", containsString("InvalidNextTokenException"));
    }

    private static void putParameter(String account, String region, String name, String value) {
        call(account, region, "ssm", "AmazonSSM.PutParameter",
                Map.of("Name", name, "Value", value, "Type", "String", "Overwrite", true)).statusCode(200);
    }

    private static void deleteBucket(String bucket) {
        String listing = given().header("Authorization", auth(ACCOUNT, REGION, "s3"))
                .get("/" + bucket + "?list-type=2").then().statusCode(200).extract().asString();
        for (String key : io.github.hectorvent.floci.core.common.XmlParser.extractAll(listing, "Key")) {
            given().header("Authorization", auth(ACCOUNT, REGION, "s3")).delete("/" + bucket + "/" + key)
                    .then().statusCode(204);
        }
        given().header("Authorization", auth(ACCOUNT, REGION, "s3")).delete("/" + bucket).then().statusCode(204);
    }

    private static ValidatableResponse receive(String queue) {
        ValidatableResponse response = call(ACCOUNT, REGION, "sqs", "AmazonSQS.ReceiveMessage",
                Map.of("QueueUrl", queue, "MaxNumberOfMessages", 10)).statusCode(200);
        List<String> receipts = response.extract().jsonPath().getList("Messages.ReceiptHandle");
        if (receipts != null) {
            for (String receipt : receipts) {
                call(ACCOUNT, REGION, "sqs", "AmazonSQS.DeleteMessage",
                        Map.of("QueueUrl", queue, "ReceiptHandle", receipt)).statusCode(200);
            }
        }
        return response;
    }

    private static void assertQueueEmpty(String queue) {
        receive(queue).statusCode(200).body("Messages", anyOf(nullValue(), empty()));
    }

    static ValidatableResponse ct(String account, String region, String operation, Map<String, Object> body) {
        return call(account, region, "cloudtrail", "CloudTrail_20131101." + operation, body);
    }

    static ValidatableResponse call(String account, String region, String service, String target, Map<String, Object> body) {
        return given().header("Authorization", auth(account, region, service)).header("X-Amz-Target", target)
                .contentType("sqs".equals(service) ? "application/x-amz-json-1.0" : "application/x-amz-json-1.1")
                .body(body).post("/").then();
    }

    static String auth(String account, String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260921/" + region + "/" + service + "/aws4_request";
    }
}
