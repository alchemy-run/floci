package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event records for versioned buckets: every record carries the version it concerns and a
 * sequencer, keys are URL-encoded as on S3, and removal records omit size and ETag.
 */
class S3VersionedNotificationServiceTest {

    private static final String BUCKET = "events";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    private RecordingLambdaInvoker invoker;
    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        invoker = new RecordingLambdaInvoker();
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, false, invoker,
                new RegionResolver("us-east-1", "000000000000"));
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        NotificationConfiguration config = new NotificationConfiguration();
        config.getLambdaFunctionConfigurations().add(new LambdaNotification("all",
                "arn:aws:lambda:us-east-1:000000000000:function:events",
                List.of("s3:ObjectCreated:*", "s3:ObjectRemoved:*"), List.of()));
        s3Service.putBucketNotificationConfiguration(BUCKET, config);
    }

    @Test
    void putRecordsCarryTheEncodedKeyVersionAndIncreasingSequencer() throws Exception {
        String key = "incoming/a b+%?#/雪.txt";
        S3Object first = put(key, "first");
        S3Object second = put(key, "second");

        JsonNode firstRecord = invoker.records().get(0);
        JsonNode secondRecord = invoker.records().get(1);
        assertEquals("ObjectCreated:Put", firstRecord.path("eventName").asText());
        JsonNode object = firstRecord.path("s3").path("object");
        assertEquals("incoming/a+b%2B%25%3F%23/%E9%9B%AA.txt", object.path("key").asText());
        assertEquals(first.getVersionId(), object.path("versionId").asText());
        assertEquals("first".length(), object.path("size").asLong());
        assertFalse(object.path("eTag").asText().isEmpty());
        String firstSequencer = object.path("sequencer").asText();
        String secondSequencer = secondRecord.path("s3").path("object").path("sequencer").asText();
        assertTrue(firstSequencer.matches("[0-9A-F]+"), firstSequencer);
        assertTrue(secondSequencer.compareTo(firstSequencer) > 0);
        assertEquals(second.getVersionId(), secondRecord.path("s3").path("object").path("versionId").asText());
    }

    @Test
    void deleteMarkerAndExplicitVersionRemovalEachReportTheirVersion() throws Exception {
        S3Object old = put("key", "old");
        put("key", "current");
        invoker.payloads.clear();

        S3Object marker = s3Service.deleteObject(BUCKET, "key");
        s3Service.deleteObject(BUCKET, "key", old.getVersionId());

        List<JsonNode> records = invoker.records();
        assertEquals(2, records.size());
        assertEquals("ObjectRemoved:DeleteMarkerCreated", records.get(0).path("eventName").asText());
        assertEquals(marker.getVersionId(), records.get(0).path("s3").path("object").path("versionId").asText());
        assertEquals("ObjectRemoved:Delete", records.get(1).path("eventName").asText());
        JsonNode removed = records.get(1).path("s3").path("object");
        assertEquals(old.getVersionId(), removed.path("versionId").asText());
        assertTrue(removed.path("size").isMissingNode());
        assertTrue(removed.path("eTag").isMissingNode());
        assertFalse(removed.path("sequencer").asText().isEmpty());
    }

    @Test
    void copyAndCompletedMultipartRecordsReportTheVersionTheyCreated() throws Exception {
        S3Object source = put("source", "copied content");
        invoker.payloads.clear();

        S3Object copy = s3Service.copyObject(BUCKET, "source", BUCKET, "incoming/copy.txt",
                source.getVersionId(), new CopyObjectOptions());
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "incoming/multipart.txt", "text/plain");
        s3Service.uploadPart(BUCKET, "incoming/multipart.txt", upload.getUploadId(), 1,
                "part".getBytes(StandardCharsets.UTF_8));
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "incoming/multipart.txt",
                upload.getUploadId(), List.of(1), null, null);

        List<JsonNode> records = invoker.records();
        assertEquals("ObjectCreated:Copy", records.get(0).path("eventName").asText());
        assertEquals(copy.getVersionId(), records.get(0).path("s3").path("object").path("versionId").asText());
        assertEquals("ObjectCreated:CompleteMultipartUpload", records.get(1).path("eventName").asText());
        assertEquals(completed.getVersionId(),
                records.get(1).path("s3").path("object").path("versionId").asText());
    }

    @Test
    void unversionedBucketRecordsOmitTheVersionId() throws Exception {
        s3Service.createBucket("plain", "us-east-1");
        NotificationConfiguration config = new NotificationConfiguration();
        config.getLambdaFunctionConfigurations().add(new LambdaNotification("all",
                "arn:aws:lambda:us-east-1:000000000000:function:events",
                List.of("s3:ObjectCreated:*"), List.of()));
        s3Service.putBucketNotificationConfiguration("plain", config);

        s3Service.putObject("plain", "key", "data".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());

        JsonNode object = invoker.records().get(0).path("s3").path("object");
        assertTrue(object.path("versionId").isMissingNode());
        assertFalse(object.path("sequencer").asText().isEmpty());
    }

    private S3Object put(String key, String body) {
        return s3Service.putObject(BUCKET, key, body.getBytes(StandardCharsets.UTF_8), "text/plain", null);
    }

    private static final class RecordingLambdaInvoker implements S3Service.LambdaInvoker {
        private final List<byte[]> payloads = new ArrayList<>();

        @Override
        public void invoke(String region, String functionName, byte[] payload, InvocationType type) {
            payloads.add(payload);
        }

        List<JsonNode> records() throws Exception {
            List<JsonNode> records = new ArrayList<>();
            for (byte[] payload : payloads) {
                records.add(MAPPER.readTree(payload).path("Records").get(0));
            }
            return records;
        }
    }
}
