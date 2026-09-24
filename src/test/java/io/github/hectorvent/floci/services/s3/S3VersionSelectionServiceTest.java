package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Selecting individual object versions: the null version, delete-marker versions, per-version
 * subresources (tags, retention, restore) and version-pinned copy sources.
 */
class S3VersionSelectionServiceTest {

    private static final String BUCKET = "versions";

    @TempDir
    Path tempDir;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, true);
        s3Service.createBucket(BUCKET, "us-east-1");
    }

    @Test
    void suspendedNullVersionSurvivesALaterVersionedWrite() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        s3Service.putBucketVersioning(BUCKET, "Suspended");
        put("key", "null version");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object current = put("key", "current after null");

        assertEquals(Set.of(current.getVersionId(), "null"), versionIds("key"));

        S3Object nullVersion = s3Service.getObject(BUCKET, "key", "null");
        assertEquals("null version", body(nullVersion));
        assertEquals("null", nullVersion.getVersionId());
        assertEquals("null", s3Service.headObject(BUCKET, "key", "null").getVersionId());
        assertEquals("current after null", body(s3Service.getObject(BUCKET, "key")));
        assertEquals(current.getVersionId(), s3Service.getObject(BUCKET, "key").getVersionId());
    }

    @Test
    void preVersioningObjectBecomesTheNullVersionOnceVersioningIsEnabled() {
        put("key", "written before versioning");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object current = put("key", "versioned write");

        assertEquals(Set.of(current.getVersionId(), "null"), versionIds("key"));
        assertEquals("written before versioning", body(s3Service.getObject(BUCKET, "key", "null")));
    }

    @Test
    void deleteMarkerKeepsThePreVersioningObjectAsNoncurrentNullVersion() {
        put("key", "written before versioning");
        s3Service.putBucketVersioning(BUCKET, "Enabled");

        S3Object marker = s3Service.deleteObject(BUCKET, "key");

        S3Service.ListVersionsResult listed = s3Service.listObjectVersions(BUCKET, "key", 100, null);
        assertEquals(2, listed.versions().size());
        assertTrue(listed.versions().stream().anyMatch(v -> "null".equals(v.getVersionId()) && !v.isLatest()));
        assertTrue(listed.versions().stream()
                .anyMatch(v -> marker.getVersionId().equals(v.getVersionId()) && v.isDeleteMarker()));
        assertEquals("written before versioning", body(s3Service.getObject(BUCKET, "key", "null")));
    }

    @Test
    void suspendedWriteReplacesANoncurrentNullVersionAndKeepsNumberedVersions() {
        s3Service.putBucketVersioning(BUCKET, "Suspended");
        put("key", "first null");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object numbered = put("key", "numbered");
        s3Service.putBucketVersioning(BUCKET, "Suspended");
        put("key", "second null");

        assertEquals(2, versionIds("key").size());
        assertEquals("second null", body(s3Service.getObject(BUCKET, "key", "null")));
        S3Object noncurrent = s3Service.listObjectVersions(BUCKET, "key", 100, null).versions().stream()
                .filter(v -> numbered.getVersionId().equals(v.getVersionId()))
                .findFirst().orElseThrow();
        assertFalse(noncurrent.isLatest());
        assertEquals("numbered", body(s3Service.getObject(BUCKET, "key", numbered.getVersionId())));
    }

    @Test
    void deletingANoncurrentNullVersionLeavesTheCurrentVersion() {
        s3Service.putBucketVersioning(BUCKET, "Suspended");
        put("key", "null version");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object current = put("key", "current");

        S3Object deleted = s3Service.deleteObject(BUCKET, "key", "null");

        assertEquals("null", deleted.getVersionId());
        assertEquals(Set.of(current.getVersionId()), versionIds("key"));
        assertEquals("current", body(s3Service.getObject(BUCKET, "key")));
        assertEquals("NoSuchVersion",
                assertThrows(AwsException.class, () -> s3Service.getObject(BUCKET, "key", "null")).getErrorCode());
    }

    @Test
    void selectingADeleteMarkerVersionIsMethodNotAllowed() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        put("key", "data");
        S3Object marker = s3Service.deleteObject(BUCKET, "key");

        S3DeleteMarkerException selected = assertThrows(S3DeleteMarkerException.class,
                () -> s3Service.headObject(BUCKET, "key", marker.getVersionId()));
        assertEquals("MethodNotAllowed", selected.getErrorCode());
        assertEquals(405, selected.getHttpStatus());
        assertTrue(selected.selectedByVersionId());
        assertEquals(marker.getVersionId(), selected.versionId());
        assertEquals(marker.getLastModified(), selected.lastModified());
        assertEquals(405, assertThrows(S3DeleteMarkerException.class,
                () -> s3Service.getObject(BUCKET, "key", marker.getVersionId())).getHttpStatus());
        assertEquals(405, assertThrows(S3DeleteMarkerException.class,
                () -> s3Service.getObjectTagging(BUCKET, "key", marker.getVersionId())).getHttpStatus());

        S3DeleteMarkerException current = assertThrows(S3DeleteMarkerException.class,
                () -> s3Service.getObject(BUCKET, "key"));
        assertEquals("NoSuchKey", current.getErrorCode());
        assertEquals(404, current.getHttpStatus());
        assertFalse(current.selectedByVersionId());
    }

    @Test
    void taggingTargetsOnlyTheSelectedVersion() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object old = put("key", "old");
        S3Object current = put("key", "current");
        s3Service.putObjectTagging(BUCKET, "key", Map.of("generation", "current"));

        S3Object tagged = s3Service.putObjectTagging(BUCKET, "key", old.getVersionId(), Map.of("generation", "old"));

        assertEquals(old.getVersionId(), tagged.getVersionId());
        assertEquals(Map.of("generation", "old"), s3Service.getObjectTagging(BUCKET, "key", old.getVersionId()));
        assertEquals(Map.of("generation", "current"), s3Service.getObjectTagging(BUCKET, "key"));
        assertEquals(Map.of("generation", "current"),
                s3Service.getObjectTagging(BUCKET, "key", current.getVersionId()));

        s3Service.deleteObjectTagging(BUCKET, "key", old.getVersionId());

        assertEquals(Map.of(), s3Service.getObjectTagging(BUCKET, "key", old.getVersionId()));
        assertEquals(Map.of("generation", "current"), s3Service.getObjectTagging(BUCKET, "key"));
        assertEquals("NoSuchVersion", assertThrows(AwsException.class,
                () -> s3Service.putObjectTagging(BUCKET, "key", "missing", Map.of())).getErrorCode());
    }

    @Test
    void taggingTheNullVersionLeavesTheCurrentVersionAlone() {
        s3Service.putBucketVersioning(BUCKET, "Suspended");
        put("key", "null version");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        put("key", "current");

        S3Object tagged = s3Service.putObjectTagging(BUCKET, "key", "null", Map.of("generation", "null"));

        assertEquals("null", tagged.getVersionId());
        assertEquals(Map.of("generation", "null"), s3Service.getObjectTagging(BUCKET, "key", "null"));
        assertEquals(Map.of(), s3Service.getObjectTagging(BUCKET, "key"));
    }

    @Test
    void retentionSetOnTheCurrentVersionIdIsVisibleWithoutAVersionId() {
        s3Service.createBucket("locked", "us-east-1");
        s3Service.putBucketVersioning("locked", "Enabled");
        s3Service.setBucketObjectLockEnabled("locked");
        S3Object current = s3Service.putObject("locked", "key", "data".getBytes(StandardCharsets.UTF_8),
                "text/plain", null);
        Instant until = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);

        s3Service.putObjectRetention("locked", "key", current.getVersionId(), "GOVERNANCE", until, false);

        assertEquals(until, s3Service.getObjectRetention("locked", "key", null).getRetainUntilDate());
        assertEquals(until, s3Service.getObjectRetention("locked", "key", current.getVersionId()).getRetainUntilDate());
        s3Service.putObjectRetention("locked", "key", current.getVersionId(), null, null, true);
    }

    @Test
    void copySourceRejectsASelectedDeleteMarkerAndReportsTheSourceVersion() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object old = put("source", "old source");
        put("source", "new source");

        S3Service.CopyObjectResult copied = s3Service.copyObjectVersion(BUCKET, "source", BUCKET, "copy",
                old.getVersionId(), new CopyObjectOptions());
        assertEquals(old.getVersionId(), copied.sourceVersionId());
        assertEquals("old source", body(s3Service.getObject(BUCKET, "copy")));

        S3Object marker = s3Service.deleteObject(BUCKET, "source");
        AwsException selected = assertThrows(AwsException.class, () -> s3Service.copyObjectVersion(
                BUCKET, "source", BUCKET, "copy", marker.getVersionId(), new CopyObjectOptions()));
        assertEquals("InvalidRequest", selected.getErrorCode());
        assertEquals(400, selected.getHttpStatus());
        AwsException current = assertThrows(AwsException.class, () -> s3Service.copyObjectVersion(
                BUCKET, "source", BUCKET, "copy", null, new CopyObjectOptions()));
        assertEquals("NoSuchKey", current.getErrorCode());
        assertFalse(current instanceof S3DeleteMarkerException);
    }

    @Test
    void uploadPartCopyReadsTheSelectedSourceVersion() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object old = put("source key/+%.txt", "old");
        put("source key/+%.txt", "a much longer current body");
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "dest", "text/plain");

        S3Service.UploadPartCopyResult part = s3Service.uploadPartCopyVersion(BUCKET, "dest",
                upload.getUploadId(), 1, BUCKET, "source key/+%.txt", old.getVersionId(), null,
                S3Service.SseCustomerHeaders.EMPTY, S3Service.SseCustomerHeaders.EMPTY);

        assertEquals(old.getVersionId(), part.sourceVersionId());
        assertEquals("old".length(), s3Service.listParts(BUCKET, "dest", upload.getUploadId())
                .getParts().get(1).getSize());
        S3Object marker = s3Service.deleteObject(BUCKET, "source key/+%.txt");
        assertEquals("InvalidRequest", assertThrows(AwsException.class, () -> s3Service.uploadPartCopyVersion(
                BUCKET, "dest", upload.getUploadId(), 2, BUCKET, "source key/+%.txt", marker.getVersionId(),
                null, S3Service.SseCustomerHeaders.EMPTY, S3Service.SseCustomerHeaders.EMPTY)).getErrorCode());
    }

    @Test
    void uploadPartCopyReportsAMissingUploadBeforeReadingTheSource() {
        assertEquals("NoSuchUpload", assertThrows(AwsException.class, () -> s3Service.uploadPartCopyVersion(
                BUCKET, "dest", "no-such-upload", 1, BUCKET, "no-such-source", null, null,
                S3Service.SseCustomerHeaders.EMPTY, S3Service.SseCustomerHeaders.EMPTY)).getErrorCode());
    }

    @Test
    void restoreOfAnArchivedVersionRecordsTheExpiryOnThatVersionOnly() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        S3Object archived = s3Service.putObject(BUCKET, "key", "archived".getBytes(StandardCharsets.UTF_8),
                "text/plain", null, new PutObjectOptions().withStorageClass("GLACIER"));
        put("key", "standard");

        assertFalse(s3Service.restoreObject(BUCKET, "key", archived.getVersionId(),
                "<RestoreRequest><Days>2</Days></RestoreRequest>"));

        Instant expiry = s3Service.headObject(BUCKET, "key", archived.getVersionId()).getRestoreExpiryDate();
        assertNotNull(expiry);
        assertTrue(expiry.isAfter(Instant.now().plusSeconds(2 * 86_400L - 1)));
        assertNull(s3Service.headObject(BUCKET, "key").getRestoreExpiryDate());
        assertTrue(s3Service.restoreObject(BUCKET, "key", archived.getVersionId(),
                "<RestoreRequest><Days>2</Days></RestoreRequest>"));
        assertEquals("InvalidObjectState", assertThrows(AwsException.class,
                () -> s3Service.restoreObject(BUCKET, "key", null, "<RestoreRequest/>")).getErrorCode());
    }

    private S3Object put(String key, String body) {
        return s3Service.putObject(BUCKET, key, body.getBytes(StandardCharsets.UTF_8), "text/plain", null);
    }

    private Set<String> versionIds(String key) {
        List<String> ids = s3Service.listObjectVersions(BUCKET, key, 100, null).versions().stream()
                .map(v -> v.getVersionId() != null ? v.getVersionId() : "null")
                .toList();
        assertEquals(ids.size(), Set.copyOf(ids).size(), "each version is listed once: " + ids);
        return Set.copyOf(ids);
    }

    private static String body(S3Object object) {
        return new String(object.getData(), StandardCharsets.UTF_8);
    }
}
