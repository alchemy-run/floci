package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQS stores a queue policy re-serialized: a one-element Action / Resource / principal list is
 * returned as the bare value. An S3 notification grant written as
 * {@code "Action":["sqs:SendMessage"],"Resource":["arn"]} reads back as plain strings.
 */
class SqsQueuePolicyNormalizationTest {

    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:events";
    private static final String BUCKET_ARN = "arn:aws:s3:::events-bucket";
    private static final String S3_GRANT = "{\n  \"Version\": \"2012-10-17\",\n  \"Statement\": [{"
            + "\"Sid\":\"AllowS3EventsFromBucket\",\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":[\"s3.amazonaws.com\"]},"
            + "\"Action\":[\"sqs:SendMessage\"],\"Resource\":[\"" + QUEUE_ARN + "\"],"
            + "\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"" + BUCKET_ARN + "\"}}}]}";

    private SqsService sqsService;

    @BeforeEach
    void setUp() {
        sqsService = new SqsService(new InMemoryStorage<>(), 30, 1048576, "http://localhost:4566",
                new MutableClock());
    }

    @Test
    void setQueueAttributesStoresSingleElementListsAsBareValues() throws Exception {
        Queue queue = sqsService.createQueue("events", null, REGION);
        sqsService.setQueueAttributes(queue.getQueueUrl(), Map.of("Policy", S3_GRANT), REGION);

        JsonNode statement = storedStatement(queue);
        assertEquals("sqs:SendMessage", statement.get("Action").asText());
        assertTrue(statement.get("Action").isTextual());
        assertEquals(QUEUE_ARN, statement.get("Resource").asText());
        assertTrue(statement.get("Resource").isTextual());
        assertEquals("s3.amazonaws.com", statement.path("Principal").get("Service").asText());
        assertEquals(BUCKET_ARN, statement.path("Condition").path("ArnEquals").get("aws:SourceArn").asText());
        assertEquals("AllowS3EventsFromBucket", statement.get("Sid").asText());
    }

    @Test
    void createQueueNormalizesAndStaysIdempotentForTheSamePolicy() throws Exception {
        Queue queue = sqsService.createQueue("events", Map.of("Policy", S3_GRANT), REGION);
        Queue again = sqsService.createQueue("events", Map.of("Policy", S3_GRANT), REGION);
        assertEquals(queue.getQueueUrl(), again.getQueueUrl());
        assertTrue(storedStatement(queue).get("Action").isTextual());
    }

    @Test
    void multiValueListsAndUnparseableDocumentsAreKept() throws Exception {
        String twoActions = "{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\"Allow\","
                + "\"Principal\":\"*\",\"Action\":[\"sqs:SendMessage\",\"sqs:ReceiveMessage\"],"
                + "\"Resource\":\"" + QUEUE_ARN + "\"}}";
        JsonNode statement = MAPPER.readTree(SqsService.normalizePolicyDocument(twoActions)).get("Statement");
        assertEquals(2, statement.get("Action").size());
        assertEquals("*", statement.get("Principal").asText());
        assertEquals("not json", SqsService.normalizePolicyDocument("not json"));
    }

    private JsonNode storedStatement(Queue queue) throws Exception {
        String policy = sqsService.getQueueAttributes(queue.getQueueUrl(), List.of("Policy"), REGION).get("Policy");
        return MAPPER.readTree(policy).get("Statement").get(0);
    }
}
