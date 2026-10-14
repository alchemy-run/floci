package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.s3.model.TopicNotification;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * SQS and SNS destinations: S3 validates them with an {@code s3:TestEvent} on every
 * PutBucketNotificationConfiguration (unless destination validation is skipped), and the object
 * events it then delivers carry the exact version each event concerns.
 */
class S3QueueNotificationServiceTest {

    private static final String BUCKET = "events";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:bucket-events";
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/bucket-events";
    private static final String TOPIC_ARN = "arn:aws:sns:us-east-1:000000000000:bucket-events";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SqsService sqsService;
    private SnsService snsService;
    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        sqsService = mock(SqsService.class);
        snsService = mock(SnsService.class);
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, false,
                sqsService, snsService, new RegionResolver("us-east-1", "000000000000"));
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
    }

    @Test
    void everyPutSendsATestEventToEachQueueAndTopic() throws Exception {
        NotificationConfiguration config = configuration();
        s3Service.putBucketNotificationConfiguration(BUCKET, config);
        s3Service.putBucketNotificationConfiguration(BUCKET, config);

        List<String> bodies = sentBodies(2);
        for (String body : bodies) {
            JsonNode event = MAPPER.readTree(body);
            assertEquals("Amazon S3", event.path("Service").asText());
            assertEquals("s3:TestEvent", event.path("Event").asText());
            assertEquals(BUCKET, event.path("Bucket").asText());
            assertFalse(event.path("Time").asText().isEmpty());
            assertFalse(event.path("RequestId").asText().isEmpty());
            assertFalse(event.path("HostId").asText().isEmpty());
            assertTrue(event.path("Records").isMissingNode());
        }
        ArgumentCaptor<String> published = ArgumentCaptor.forClass(String.class);
        verify(snsService, times(2)).publish(eq(TOPIC_ARN), any(), published.capture(),
                eq("Amazon S3 Notification"), eq("us-east-1"));
        assertEquals("s3:TestEvent", MAPPER.readTree(published.getValue()).path("Event").asText());
    }

    @Test
    void skippingDestinationValidationSendsNoTestEvent() {
        s3Service.putBucketNotificationConfiguration(BUCKET, configuration(), true);
        verify(sqsService, never()).sendMessage(anyString(), anyString(), any(), any());
        verify(snsService, never()).publish(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void queueRecordsCarryTheVersionEachEventConcerns() throws Exception {
        s3Service.putBucketNotificationConfiguration(BUCKET, configuration(), true);
        S3Object first = put("incoming/a.txt", "first");
        S3Object copy = s3Service.copyObject(BUCKET, "incoming/a.txt", BUCKET, "incoming/b.txt",
                first.getVersionId(), new CopyObjectOptions());
        S3Object marker = s3Service.deleteObject(BUCKET, "incoming/b.txt");
        S3Object removed = s3Service.deleteObject(BUCKET, "incoming/b.txt", marker.getVersionId());

        List<JsonNode> records = new ArrayList<>();
        for (String body : sentBodies(4)) {
            records.add(MAPPER.readTree(body).path("Records").get(0));
        }
        assertEquals("ObjectCreated:Put", records.get(0).path("eventName").asText());
        assertEquals(first.getVersionId(), versionOf(records.get(0)));
        assertEquals("ObjectCreated:Copy", records.get(1).path("eventName").asText());
        assertEquals(copy.getVersionId(), versionOf(records.get(1)));
        assertEquals("ObjectRemoved:DeleteMarkerCreated", records.get(2).path("eventName").asText());
        assertEquals(marker.getVersionId(), versionOf(records.get(2)));
        assertEquals("ObjectRemoved:Delete", records.get(3).path("eventName").asText());
        assertEquals(removed.getVersionId(), versionOf(records.get(3)));
        assertEquals(marker.getVersionId(), versionOf(records.get(3)));
        for (int i = 1; i < records.size(); i++) {
            String previous = records.get(i - 1).path("s3").path("object").path("sequencer").asText();
            String current = records.get(i).path("s3").path("object").path("sequencer").asText();
            assertTrue(current.compareTo(previous) > 0, previous + " then " + current);
        }
    }

    private List<String> sentBodies(int count) {
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(sqsService, times(count))
                .sendMessage(eq(QUEUE_URL), bodies.capture(), eq(0), eq("us-east-1"));
        List<String> values = new ArrayList<>(bodies.getAllValues());
        clearInvocations(sqsService);
        return values;
    }

    private static String versionOf(JsonNode record) {
        return record.path("s3").path("object").path("versionId").asText();
    }

    private static NotificationConfiguration configuration() {
        NotificationConfiguration config = new NotificationConfiguration();
        config.getQueueConfigurations().add(new QueueNotification("queue", QUEUE_ARN,
                List.of("s3:ObjectCreated:*", "s3:ObjectRemoved:*")));
        config.getTopicConfigurations().add(new TopicNotification("topic", TOPIC_ARN,
                List.of("s3:ObjectCreated:*")));
        return config;
    }

    private S3Object put(String key, String body) {
        return s3Service.putObject(BUCKET, key, body.getBytes(StandardCharsets.UTF_8), "text/plain", null);
    }
}
