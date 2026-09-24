package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for S3 features fixed in issues #119, #236, #237.
 */
@DisplayName("S3 Features — pagination, versioning, public access block")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3FeaturesTest {

    private static S3Client s3;

    // Dedicated buckets per feature group — avoids ordering conflicts with S3Test
    private static final String BUCKET_VERSIONS  = "compat-versions-bucket";
    private static final String BUCKET_PAB       = "compat-pab-bucket";
    private static final String BUCKET_PAGINATE  = "compat-paginate-bucket";
    private static final String BUCKET_334       = "compat-334-bucket";

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        createBucket(BUCKET_VERSIONS);
        createBucket(BUCKET_PAB);
        createBucket(BUCKET_PAGINATE);
        createBucket(BUCKET_334);
    }

    @AfterAll
    static void cleanup() {
        if (s3 == null) return;
        deleteBucketContents(BUCKET_VERSIONS);
        deleteBucketContents(BUCKET_PAB);
        deleteBucketContents(BUCKET_PAGINATE);
        deleteBucketContents(BUCKET_334);
        quietDeleteBucket(BUCKET_VERSIONS);
        quietDeleteBucket(BUCKET_PAB);
        quietDeleteBucket(BUCKET_PAGINATE);
        quietDeleteBucket(BUCKET_334);
        s3.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #237 — listObjectVersionsPaginator must not NPE (IsTruncated=null)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Iterating listObjectVersionsPaginator on a versioning-disabled bucket used to throw
     * NullPointerException because IsTruncated was missing from the XML response.
     */
    @Test
    @Order(10)
    @DisplayName("#237 listObjectVersionsPaginator: non-versioned bucket does not NPE")
    void listObjectVersionsPaginatorNonVersionedBucketDoesNotNpe() {
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("plain.txt").build(),
                RequestBody.fromString("content"));

        // Must not throw NullPointerException
        ListObjectVersionsIterable pages = s3.listObjectVersionsPaginator(
                ListObjectVersionsRequest.builder().bucket(BUCKET_VERSIONS).build());

        assertThatNoException().isThrownBy(() -> {
            for (ListObjectVersionsResponse page : pages) {
                assertThat(page.isTruncated()).isNotNull();
            }
        });
    }

    @Test
    @Order(11)
    @DisplayName("#237 listObjectVersionsPaginator: versioned bucket returns isTruncated=false")
    void listObjectVersionsPaginatorVersionedBucketReturnsTruncatedFlag() {
        // Enable versioning and put two versions of the same key
        s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(BUCKET_VERSIONS)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());

        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("versioned.txt").build(),
                RequestBody.fromString("v1"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("versioned.txt").build(),
                RequestBody.fromString("v2"));

        List<ObjectVersion> collected = new ArrayList<>();
        for (ListObjectVersionsResponse page :
                s3.listObjectVersionsPaginator(ListObjectVersionsRequest.builder()
                        .bucket(BUCKET_VERSIONS).build())) {
            assertThat(page.isTruncated()).isNotNull();
            collected.addAll(page.versions());
        }

        assertThat(collected).hasSizeGreaterThanOrEqualTo(2);
        assertThat(collected).anyMatch(v -> "versioned.txt".equals(v.key()));
    }

    @Test
    @Order(12)
    @DisplayName("#237 listObjectVersionsPaginator: paginates correctly with maxKeys")
    void listObjectVersionsPaginatorPaginates() {
        // Put additional versioned objects to exceed a single page
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("a.txt").build(),
                RequestBody.fromString("a1"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("a.txt").build(),
                RequestBody.fromString("a2"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("b.txt").build(),
                RequestBody.fromString("b1"));

        List<ObjectVersion> allVersions = new ArrayList<>();
        ListObjectVersionsIterable pages = s3.listObjectVersionsPaginator(
                ListObjectVersionsRequest.builder()
                        .bucket(BUCKET_VERSIONS)
                        .maxKeys(2)
                        .build());

        for (ListObjectVersionsResponse page : pages) {
            assertThat(page.isTruncated()).isNotNull();
            allVersions.addAll(page.versions());
        }

        // We put at least 5 versions total — should all be collected across pages
        assertThat(allVersions.size()).isGreaterThanOrEqualTo(5);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #334 — listObjectVersions must return non-versioned objects
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Objects uploaded to a bucket that has never had versioning enabled must appear in
     * ListObjectVersions with VersionId="null" (the literal string, per AWS spec).
     */
    @Test
    @Order(13)
    @DisplayName("#334 listObjectVersions: non-versioned bucket returns objects with VersionId=null")
    void listObjectVersionsNonVersionedBucketReturnsObjects() {
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_334).key("file-a.txt").build(),
                RequestBody.fromString("content-a"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_334).key("file-b.txt").build(),
                RequestBody.fromString("content-b"));

        ListObjectVersionsResponse response = s3.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(BUCKET_334).build());

        List<ObjectVersion> versions = response.versions();
        assertThat(versions).hasSize(2);

        List<String> keys = versions.stream().map(ObjectVersion::key).toList();
        assertThat(keys).containsExactlyInAnyOrder("file-a.txt", "file-b.txt");

        // AWS returns the literal string "null" for objects uploaded without versioning
        assertThat(versions).allMatch(v -> "null".equals(v.versionId()));
        assertThat(versions).allMatch(ObjectVersion::isLatest);
    }

    /**
     * Objects uploaded before versioning was enabled must appear in ListObjectVersions
     * alongside objects uploaded after versioning was enabled.
     * Pre-versioning objects appear with VersionId="null"; post-versioning objects have a UUID.
     */
    @Test
    @Order(14)
    @DisplayName("#334 listObjectVersions: pre-versioning objects appear alongside versioned entries")
    void listObjectVersionsPreVersioningObjectsAppearsWithNullVersionId() {
        // plain.txt was put at order 10, before versioning was enabled at order 11.
        // It should appear in the listing with VersionId="null".
        ListObjectVersionsResponse response = s3.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(BUCKET_VERSIONS).build());

        List<ObjectVersion> all = response.versions();

        // Pre-versioning object
        List<ObjectVersion> plainVersions = all.stream()
                .filter(v -> "plain.txt".equals(v.key()))
                .toList();
        assertThat(plainVersions).hasSize(1);
        assertThat(plainVersions.get(0).versionId()).isEqualTo("null");
        assertThat(plainVersions.get(0).isLatest()).isTrue();

        // Versioned objects uploaded after versioning was enabled must have UUID version IDs
        List<ObjectVersion> versioned = all.stream()
                .filter(v -> "versioned.txt".equals(v.key()))
                .toList();
        assertThat(versioned).hasSizeGreaterThanOrEqualTo(2);
        assertThat(versioned).allMatch(v -> v.versionId() != null && !"null".equals(v.versionId()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #236 — PutPublicAccessBlock must not return BucketAlreadyOwnedByYou
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("#236 putPublicAccessBlock succeeds on existing bucket")
    void putPublicAccessBlockSucceeds() {
        assertThatNoException().isThrownBy(() ->
                s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                        .bucket(BUCKET_PAB)
                        .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                                .blockPublicAcls(true)
                                .ignorePublicAcls(true)
                                .blockPublicPolicy(true)
                                .restrictPublicBuckets(true)
                                .build())
                        .build()));
    }

    @Test
    @Order(21)
    @DisplayName("#236 getPublicAccessBlock returns stored configuration")
    void getPublicAccessBlockReturnsConfig() {
        GetPublicAccessBlockResponse response = s3.getPublicAccessBlock(
                GetPublicAccessBlockRequest.builder().bucket(BUCKET_PAB).build());

        PublicAccessBlockConfiguration config = response.publicAccessBlockConfiguration();
        assertThat(config.blockPublicAcls()).isTrue();
        assertThat(config.ignorePublicAcls()).isTrue();
        assertThat(config.blockPublicPolicy()).isTrue();
        assertThat(config.restrictPublicBuckets()).isTrue();
    }

    @Test
    @Order(22)
    @DisplayName("#236 putPublicAccessBlock can be updated")
    void putPublicAccessBlockCanBeUpdated() {
        s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                .bucket(BUCKET_PAB)
                .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(false)
                        .ignorePublicAcls(false)
                        .blockPublicPolicy(false)
                        .restrictPublicBuckets(false)
                        .build())
                .build());

        GetPublicAccessBlockResponse response = s3.getPublicAccessBlock(
                GetPublicAccessBlockRequest.builder().bucket(BUCKET_PAB).build());

        assertThat(response.publicAccessBlockConfiguration().blockPublicAcls()).isFalse();
    }

    @Test
    @Order(23)
    @DisplayName("#236 deletePublicAccessBlock removes the configuration")
    void deletePublicAccessBlockRemovesConfig() {
        s3.deletePublicAccessBlock(DeletePublicAccessBlockRequest.builder()
                .bucket(BUCKET_PAB).build());

        assertThatThrownBy(() -> s3.getPublicAccessBlock(
                GetPublicAccessBlockRequest.builder().bucket(BUCKET_PAB).build()))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #119 — ListObjectsV2 pagination fields
    // ─────────────────────────────────────────────────────────────────────────

    @BeforeEach
    void setupPaginateBucket() {
        // Ensure objects are present before pagination tests run.
        // Idempotent — SDK suppresses errors if already exists.
    }

    @Test
    @Order(30)
    @DisplayName("#119 listObjectsV2Paginator collects all objects across pages")
    void listObjectsV2PaginatorCollectsAllObjects() {
        // Put 5 objects
        for (int i = 1; i <= 5; i++) {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(BUCKET_PAGINATE).key("file-" + i + ".txt").build(),
                    RequestBody.fromString("content " + i));
        }

        List<S3Object> collected = new ArrayList<>();
        ListObjectsV2Iterable pages = s3.listObjectsV2Paginator(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .maxKeys(2)
                        .build());

        for (ListObjectsV2Response page : pages) {
            collected.addAll(page.contents());
        }

        assertThat(collected).hasSize(5);
        assertThat(collected.stream().map(S3Object::key).toList())
                .containsExactlyInAnyOrder(
                        "file-1.txt", "file-2.txt", "file-3.txt", "file-4.txt", "file-5.txt");
    }

    @Test
    @Order(31)
    @DisplayName("#119 listObjectsV2 with startAfter skips keys up to and including the marker")
    void listObjectsV2StartAfterSkipsKeys() {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .startAfter("file-3.txt")
                        .build());

        List<String> keys = response.contents().stream().map(S3Object::key).toList();
        assertThat(keys).doesNotContain("file-1.txt", "file-2.txt", "file-3.txt");
        assertThat(keys).contains("file-4.txt", "file-5.txt");
    }

    @Test
    @Order(32)
    @DisplayName("#119 listObjectsV2 response echoes startAfter when provided")
    void listObjectsV2ResponseEchoesStartAfter() {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .startAfter("file-2.txt")
                        .build());

        assertThat(response.startAfter()).isEqualTo("file-2.txt");
    }

    @Test
    @Order(33)
    @DisplayName("#119 listObjectsV2 first truncated page contains NextContinuationToken")
    void listObjectsV2TruncatedPageHasNextToken() {
        ListObjectsV2Response firstPage = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .maxKeys(2)
                        .build());

        assertThat(firstPage.isTruncated()).isTrue();
        assertThat(firstPage.nextContinuationToken()).isNotNull().isNotEmpty();
    }

    @Test
    @Order(34)
    @DisplayName("#119 listObjectsV2 continuation token resumes from correct position")
    void listObjectsV2ContinuationTokenResumesCorrectly() {
        // Page 1
        ListObjectsV2Response page1 = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .maxKeys(3)
                        .build());
        assertThat(page1.isTruncated()).isTrue();
        List<String> page1Keys = page1.contents().stream().map(S3Object::key).toList();

        // Page 2 using token
        ListObjectsV2Response page2 = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .maxKeys(3)
                        .continuationToken(page1.nextContinuationToken())
                        .build());

        assertThat(page2.isTruncated()).isFalse();
        assertThat(page2.continuationToken()).isEqualTo(page1.nextContinuationToken());

        // No key should appear on both pages
        List<String> page2Keys = page2.contents().stream().map(S3Object::key).toList();
        assertThat(page2Keys).doesNotContainAnyElementsOf(page1Keys);

        // Together they must cover all 5 objects
        List<String> allKeys = new ArrayList<>(page1Keys);
        allKeys.addAll(page2Keys);
        assertThat(allKeys).containsExactlyInAnyOrder(
                "file-1.txt", "file-2.txt", "file-3.txt", "file-4.txt", "file-5.txt");
    }

    @Test
    @Order(40)
    @DisplayName("Bulk delete uses listed null version IDs to empty an unversioned bucket")
    void bulkDeleteListedNullVersionsEmptiesBucket() {
        String bucket = TestFixtures.uniqueName("sdk-null-version-cleanup");
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        for (String key : List.of("nested/a & b.txt", "nested/b.txt", "nested/c.txt", "x.txt", "z.txt")) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString(key));
        }
        ListObjectVersionsResponse listed = s3.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).build());
        assertThat(listed.versions()).hasSize(5).allMatch(v -> "null".equals(v.versionId()));
        assertBucketNotEmpty(bucket);

        bulkDeleteVersionPages(bucket, 5);
    }

    @Test
    @Order(41)
    @DisplayName("Bulk delete resumes pagination after deleting versions and the page marker")
    void bulkDeleteVersionPagesAndDeleteMarkersEmptiesBucket() {
        String bucket = TestFixtures.uniqueName("sdk-version-page-cleanup");
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key("a/plain.txt").build(),
                RequestBody.fromString("unversioned"));
        s3.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(bucket)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED).build()).build());
        for (int i = 0; i < 5; i++) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key("history/file.txt").build(),
                    RequestBody.fromString("version-" + i));
        }
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key("history/file.txt").build());
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key("z.txt").build(), RequestBody.fromString("last"));
        assertBucketNotEmpty(bucket);

        bulkDeleteVersionPages(bucket, 8);
    }

    @Test
    @Order(42)
    @DisplayName("Encryption defaults, KMS settings, blocked types and owner mismatches round-trip through the SDK")
    void encryptionConfigurationAndExpectedOwnerRoundTrip() {
        String bucket = TestFixtures.uniqueName("sdk-encryption-owner");
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        try {
            ServerSideEncryptionRule defaults = s3.getBucketEncryption(r -> r.bucket(bucket))
                    .serverSideEncryptionConfiguration().rules().get(0);
            assertThat(defaults.applyServerSideEncryptionByDefault().sseAlgorithm()).isEqualTo(ServerSideEncryption.AES256);
            assertThat(defaults.blockedEncryptionTypes().encryptionTypeAsStrings()).containsExactly("NONE");
            String kmsKey = "arn:aws:kms:us-east-1:000000000000:key/compat-encryption";
            for (String blocked : List.of("SSE-C", "NONE")) {
                s3.putBucketEncryption(r -> r.bucket(bucket).serverSideEncryptionConfiguration(c -> c.rules(rule -> rule
                        .applyServerSideEncryptionByDefault(d -> d.sseAlgorithm(ServerSideEncryption.AWS_KMS).kmsMasterKeyID(kmsKey))
                        .bucketKeyEnabled(true)
                        .blockedEncryptionTypes(types -> types.encryptionTypeWithStrings(blocked)))));
                ServerSideEncryptionRule stored = s3.getBucketEncryption(r -> r.bucket(bucket))
                        .serverSideEncryptionConfiguration().rules().get(0);
                assertThat(stored.applyServerSideEncryptionByDefault().kmsMasterKeyID()).isEqualTo(kmsKey);
                assertThat(stored.bucketKeyEnabled()).isTrue();
                assertThat(stored.blockedEncryptionTypes().encryptionTypeAsStrings()).containsExactly(blocked);
            }
            assertThatThrownBy(() -> s3.getBucketLocation(r -> r.bucket(bucket).expectedBucketOwner("999999999999")))
                    .isInstanceOfSatisfying(S3Exception.class, error -> {
                        assertThat(error.statusCode()).isEqualTo(403);
                        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("AccessDenied");
                    });
            assertThatThrownBy(() -> s3.deleteBucketEncryption(r -> r.bucket(bucket).expectedBucketOwner("999999999999")))
                    .isInstanceOfSatisfying(S3Exception.class, error -> assertThat(error.statusCode()).isEqualTo(403));
            assertThat(s3.getBucketEncryption(r -> r.bucket(bucket)).serverSideEncryptionConfiguration().rules().get(0)
                    .applyServerSideEncryptionByDefault().kmsMasterKeyID()).isEqualTo(kmsKey);
            s3.deleteBucketEncryption(r -> r.bucket(bucket));
            ServerSideEncryptionRule reset = s3.getBucketEncryption(r -> r.bucket(bucket))
                    .serverSideEncryptionConfiguration().rules().get(0);
            assertThat(reset.applyServerSideEncryptionByDefault().sseAlgorithm()).isEqualTo(ServerSideEncryption.AES256);
            assertThat(reset.applyServerSideEncryptionByDefault().kmsMasterKeyID()).isNull();
            assertThat(reset.bucketKeyEnabled()).isFalse();
            assertThat(reset.blockedEncryptionTypes().encryptionTypeAsStrings()).containsExactly("NONE");
        } finally {
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        }
    }

    private static void bulkDeleteVersionPages(String bucket, int expectedCount) {
        String keyMarker = null;
        String versionMarker = null;
        int deletedCount = 0;
        boolean truncated = true;
        for (int pageNumber = 0; pageNumber < expectedCount && truncated; pageNumber++) {
            ListObjectVersionsResponse page = s3.listObjectVersions(ListObjectVersionsRequest.builder()
                    .bucket(bucket).maxKeys(2).keyMarker(keyMarker).versionIdMarker(versionMarker).build());
            List<ObjectIdentifier> objects = new ArrayList<>();
            for (ObjectVersion version : page.versions()) {
                objects.add(ObjectIdentifier.builder().key(version.key()).versionId(version.versionId()).build());
            }
            for (DeleteMarkerEntry marker : page.deleteMarkers()) {
                objects.add(ObjectIdentifier.builder().key(marker.key()).versionId(marker.versionId()).build());
            }
            assertThat(objects).isNotEmpty();
            DeleteObjectsResponse deleted = s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket)
                    .delete(Delete.builder().objects(objects).quiet(true).build()).build());
            assertThat(deleted.errors()).isEmpty();
            assertThat(deleted.deleted()).isEmpty();
            deletedCount += objects.size();
            truncated = page.isTruncated();
            keyMarker = page.nextKeyMarker();
            versionMarker = page.nextVersionIdMarker();
        }
        assertThat(truncated).isFalse();
        assertThat(deletedCount).isEqualTo(expectedCount);
        ListObjectVersionsResponse remaining = s3.listObjectVersions(
                ListObjectVersionsRequest.builder().bucket(bucket).build());
        assertThat(remaining.versions()).isEmpty();
        assertThat(remaining.deleteMarkers()).isEmpty();
        assertThat(s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents()).isEmpty();
        s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        assertThatThrownBy(() -> s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()))
                .isInstanceOfSatisfying(S3Exception.class, error -> assertThat(error.statusCode()).isEqualTo(404));
    }

    private static void assertBucketNotEmpty(String bucket) {
        assertThatThrownBy(() -> s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build()))
                .isInstanceOfSatisfying(S3Exception.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).isEqualTo("BucketNotEmpty"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void createBucket(String bucket) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {}
    }

    private static void deleteBucketContents(String bucket) {
        try {
            // Delete all ordinary objects
            String token = null;
            do {
                ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket).continuationToken(token).build());
                for (S3Object obj : resp.contents()) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build());
                }
                token = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (token != null);

            // Delete all versions and delete-markers
            String keyMarker = null;
            String versionMarker = null;
            do {
                ListObjectVersionsResponse resp = s3.listObjectVersions(
                        ListObjectVersionsRequest.builder()
                                .bucket(bucket).keyMarker(keyMarker).versionIdMarker(versionMarker).build());
                for (ObjectVersion v : resp.versions()) {
                    s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket).key(v.key()).versionId(v.versionId()).build());
                }
                for (DeleteMarkerEntry dm : resp.deleteMarkers()) {
                    s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket).key(dm.key()).versionId(dm.versionId()).build());
                }
                keyMarker = resp.isTruncated() ? resp.nextKeyMarker() : null;
                versionMarker = resp.isTruncated() ? resp.nextVersionIdMarker() : null;
            } while (keyMarker != null);
        } catch (Exception ignored) {}
    }

    private static void quietDeleteBucket(String bucket) {
        try { s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build()); }
        catch (Exception ignored) {}
    }
}
