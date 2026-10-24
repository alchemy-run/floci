package io.github.hectorvent.floci.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3tables.model.S3Table;
import io.github.hectorvent.floci.services.s3tables.model.TableBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3TablesServiceTest {
    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "unit-test-table-bucket";

    private S3TablesService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
            }
        };
        service = new S3TablesService(storageFactory, new RegionResolver(REGION, ACCOUNT_ID));
    }

    @Test
    void isolatesSameBucketNameByRegion() {
        TableBucket east = service.createTableBucket(BUCKET, null, null, Map.of(), REGION);
        TableBucket west = service.createTableBucket(BUCKET, null, null, Map.of(), "eu-west-1");

        assertEquals("arn:aws:s3tables:us-east-1:000000000000:bucket/" + BUCKET, east.getArn());
        assertEquals("arn:aws:s3tables:eu-west-1:000000000000:bucket/" + BUCKET, west.getArn());
        assertEquals(List.of(east), service.listTableBuckets(null, REGION));
        assertEquals(List.of(west), service.listTableBuckets(null, "eu-west-1"));
    }

    @Test
    void rejectsDuplicateBucketsAndNonEmptyParents() {
        TableBucket bucket = createBucket();
        String arn = bucket.getArn();

        assertError("ConflictException", () -> service.createTableBucket(BUCKET, null, null, Map.of(), REGION));

        service.createNamespace(arn, List.of("analytics"), REGION);
        assertError("BadRequestException", () -> service.deleteTableBucket(arn, REGION));

        createTable(arn, "analytics", "events");
        assertError("ConflictException", () -> service.deleteNamespace(arn, "analytics", REGION));

        service.deleteTable(arn, "analytics", "events", REGION);
        service.deleteNamespace(arn, "analytics", REGION);
        service.deleteTableBucket(arn, REGION);
        assertError("NotFoundException", () -> service.getTableBucket(arn, REGION));
    }

    @Test
    void updatesMetadataOnlyWithTheCurrentVersionToken() {
        String arn = createBucketWithNamespace("analytics");
        S3Table created = createTable(arn, "analytics", "events");
        String originalToken = created.getVersionToken();
        assertError("BadRequestException", () -> service.updateTableMetadataLocation(arn, "analytics", "events",
                "s3://warehouse/events/metadata/v2.json", null, REGION));
        assertEquals(originalToken, created.getVersionToken());

        S3Table updated = service.updateTableMetadataLocation(arn, "analytics", "events",
                "s3://warehouse/events/metadata/v2.json", originalToken, REGION);

        assertEquals("s3://warehouse/events/metadata/v2.json", updated.getMetadataLocation());
        assertNotEquals(originalToken, updated.getVersionToken());
        assertError("ConflictException", () -> service.updateTableMetadataLocation(arn, "analytics", "events",
                "s3://warehouse/events/metadata/v3.json", originalToken, REGION));
        assertEquals("s3://warehouse/events/metadata/v2.json",
                service.getTable(arn, "analytics", "events", REGION).getMetadataLocation());
    }

    @Test
    void renameMovesTableAndRotatesItsVersionToken() {
        String arn = createBucketWithNamespace("analytics");
        service.createNamespace(arn, List.of("reporting"), REGION);
        S3Table created = createTable(arn, "analytics", "events");
        String originalToken = created.getVersionToken();
        String originalArn = created.getArn();
        String warehouse = created.getWarehouseLocation();

        S3Table renamed = service.renameTable(arn, "analytics", "events", "reporting", "daily_events",
                originalToken, REGION);

        assertEquals("reporting", renamed.getNamespace());
        assertEquals("daily_events", renamed.getName());
        assertEquals(originalArn, renamed.getArn());
        assertEquals(warehouse, renamed.getWarehouseLocation());
        assertEquals(renamed, service.getTableByArn(originalArn, REGION));
        assertNotEquals(originalToken, renamed.getVersionToken());
        assertError("NotFoundException", () -> service.getTable(arn, "analytics", "events", REGION));
        assertEquals(renamed, service.getTable(arn, "reporting", "daily_events", REGION));
    }

    @Test
    void roundTripsPoliciesAndTypedMaintenanceConfigurations() {
        String arn = createBucketWithNamespace("analytics");
        createTable(arn, "analytics", "events");
        Map<String, Object> bucketMaintenance = Map.of("unreferencedFileRemoval", Map.of("status", "enabled"));
        Map<String, Object> tableMaintenance = Map.of("status", "enabled");

        service.putTableBucketPolicy(arn, "{\"Version\":\"2012-10-17\"}", REGION);
        service.putTableBucketMaintenance(arn, "UNREFERENCED_FILE_REMOVAL", bucketMaintenance, REGION);
        service.putTablePolicy(arn, "analytics", "events", "{\"Statement\":[]}", REGION);
        service.putTableMaintenance(arn, "analytics", "events", "ICEBERG_COMPACTION", tableMaintenance, REGION);

        assertEquals("{\"Version\":\"2012-10-17\"}", service.getTableBucketPolicy(arn, REGION));
        assertEquals(bucketMaintenance, service.getTableBucketMaintenance(arn, "UNREFERENCED_FILE_REMOVAL", REGION));
        assertEquals(Map.of("UNREFERENCED_FILE_REMOVAL", bucketMaintenance),
                service.getTableBucketMaintenanceConfigurations(arn, REGION));
        assertEquals("{\"Statement\":[]}", service.getTablePolicy(arn, "analytics", "events", REGION));
        assertEquals(tableMaintenance,
                service.getTableMaintenance(arn, "analytics", "events", "ICEBERG_COMPACTION", REGION));
        assertEquals(Map.of("ICEBERG_COMPACTION", tableMaintenance),
                service.getTableMaintenanceConfigurations(arn, "analytics", "events", REGION));

        service.deleteTableBucketPolicy(arn, REGION);
        service.deleteTablePolicy(arn, "analytics", "events", REGION);
        assertError("NotFoundException", () -> service.getTableBucketPolicy(arn, REGION));
        assertError("NotFoundException", () -> service.getTablePolicy(arn, "analytics", "events", REGION));
    }

    @Test
    void persistsWarehouseCommitAndMaintenanceStateAcrossRestart(@TempDir Path directory) {
        service = persistentService(directory);
        String arn = createBucketWithNamespace("analytics");
        S3Table table = service.createTable(arn, "analytics", "events", "ICEBERG",
                Map.of("iceberg", Map.of("schema", Map.of("fields", List.of(Map.of("name", "id", "type", "long"))))),
                null, null, Map.of("owner", "analytics"), REGION);
        String warehouse = table.getWarehouseLocation();
        String originalToken = table.getVersionToken();
        assertTrue(warehouse.startsWith("s3://"));
        assertNull(table.getMetadataLocation());
        assertEquals(Map.of("status", "Not_Yet_Run"),
                service.getTableMaintenanceJobStatus(arn, "analytics", "events", REGION).get("icebergCompaction"));

        service.putTableMaintenance(arn, "analytics", "events", "icebergCompaction", Map.of("status", "disabled"), REGION);
        service.putTableBucketMaintenance(arn, "icebergUnreferencedFileRemoval", Map.of("status", "disabled"), REGION);
        service.putTablePolicy(arn, "analytics", "events", "{\"Statement\":[]}", REGION);
        service.updateTableMetadataLocation(arn, "analytics", "events", warehouse + "/metadata/v1.json", originalToken, REGION);
        String committedToken = table.getVersionToken();
        service = persistentService(directory);

        S3Table restored = service.getTable(arn, "analytics", "events", REGION);
        assertEquals(table.getArn(), restored.getArn());
        assertEquals(warehouse, restored.getWarehouseLocation());
        assertEquals(warehouse + "/metadata/v1.json", restored.getMetadataLocation());
        assertEquals(committedToken, restored.getVersionToken());
        assertEquals(table.getMetadata(), restored.getMetadata());
        assertEquals("{\"Statement\":[]}", service.getTablePolicy(arn, "analytics", "events", REGION));
        assertEquals(Map.of("owner", "analytics"), service.listTagsForResource(restored.getArn(), REGION));
        assertEquals(Map.of(
                "icebergCompaction", Map.of("status", "Disabled"),
                "icebergSnapshotManagement", Map.of("status", "Not_Yet_Run"),
                "icebergUnreferencedFileRemoval", Map.of("status", "Disabled")),
                service.getTableMaintenanceJobStatus(arn, "analytics", "events", REGION));
        service.putTableMaintenance(arn, "analytics", "events", "icebergCompaction", Map.of("status", "enabled"), REGION);
        assertEquals(Map.of("status", "Not_Yet_Run"),
                service.getTableMaintenanceJobStatus(arn, "analytics", "events", REGION).get("icebergCompaction"));
        assertEquals(committedToken, restored.getVersionToken());
        assertError("ConflictException", () -> service.updateTableMetadataLocation(arn, "analytics", "events",
                warehouse + "/metadata/stale.json", originalToken, REGION));
    }

    @Test
    void backfillsLegacyWarehouseWithoutChangingIdentityOrVersion(@TempDir Path directory) {
        service = persistentService(directory);
        String arn = createBucketWithNamespace("analytics");
        S3Table table = createTable(arn, "analytics", "events");
        String token = table.getVersionToken();
        table.setWarehouseLocation(null);
        service.putTablePolicy(arn, "analytics", "events", "{\"Statement\":[]}", REGION);
        service = persistentService(directory);
        String warehouse = service.getTable(arn, "analytics", "events", REGION).getWarehouseLocation();
        assertTrue(warehouse.startsWith("s3://"));
        service = persistentService(directory);
        assertEquals(warehouse, service.getTableByArn(table.getArn(), REGION).getWarehouseLocation());
        assertEquals(token, service.getTableByArn(table.getArn(), REGION).getVersionToken());
    }

    @Test
    void isolatesTableIdentityTagsAndMaintenanceByNamespaceAndRegion() {
        String arn = createBucketWithNamespace("analytics");
        service.createNamespace(arn, List.of("reporting"), REGION);
        S3Table analytics = createTable(arn, "analytics", "events");
        S3Table reporting = createTable(arn, "reporting", "events");
        assertNotEquals(analytics.getArn(), reporting.getArn());
        assertNotEquals(analytics.getWarehouseLocation(), reporting.getWarehouseLocation());
        service.tagResource(analytics.getArn(), Map.of("owner", "analytics", "remove", "yes"), REGION);
        service.untagResource(analytics.getArn(), List.of("remove", "absent"), REGION);
        service.tagResource(arn, Map.of("owner", "bucket"), REGION);
        assertEquals(Map.of("owner", "analytics"), service.listTagsForResource(analytics.getArn(), REGION));
        assertEquals(Map.of(), service.listTagsForResource(reporting.getArn(), REGION));
        assertEquals(Map.of("owner", "bucket"), service.listTagsForResource(arn, REGION));
        service.putTableMaintenance(arn, "analytics", "events", "icebergCompaction", Map.of("status", "disabled"), REGION);
        assertEquals(Map.of("status", "Not_Yet_Run"),
                service.getTableMaintenanceJobStatus(arn, "reporting", "events", REGION).get("icebergCompaction"));
        assertError("NotFoundException", () -> service.getTableMaintenanceJobStatus(arn, "missing", "events", REGION));
        assertError("NotFoundException", () -> service.getTableMaintenanceJobStatus(arn, "analytics", "events", "eu-west-1"));
        assertError("NotFoundException", () -> service.listTagsForResource(analytics.getArn(), "eu-west-1"));
        assertError("NotFoundException", () -> service.getTableByArn(
                analytics.getArn().replace(ACCOUNT_ID, "111111111111"), REGION));
        service.deleteTable(arn, "analytics", "events", REGION);
        assertError("NotFoundException", () -> service.listTagsForResource(analytics.getArn(), REGION));
        assertError("NotFoundException", () -> service.getTableMaintenanceJobStatus(arn, "analytics", "events", REGION));
        S3Table recreated = createTable(arn, "analytics", "events");
        assertNotEquals(analytics.getArn(), recreated.getArn());
        assertNotEquals(analytics.getWarehouseLocation(), recreated.getWarehouseLocation());
    }

    @Test
    void rejectsAmbiguousLegacyTableArnsWithoutChangingEitherTable() {
        String arn = createBucketWithNamespace("analytics");
        service.createNamespace(arn, List.of("reporting"), REGION);
        S3Table analytics = createTable(arn, "analytics", "events");
        S3Table reporting = createTable(arn, "reporting", "events");
        analytics.setArn(arn + "/table/events");
        reporting.setArn(analytics.getArn());
        assertError("ConflictException", () -> service.getTableByArn(analytics.getArn(), REGION));
        assertError("ConflictException", () -> service.tagResource(analytics.getArn(), Map.of("owner", "ambiguous"), REGION));
        assertEquals(Map.of(), analytics.getTags());
        assertEquals(Map.of(), reporting.getTags());
        assertEquals(analytics, service.getTable(arn, "analytics", "events", REGION));
        assertEquals(reporting, service.getTable(arn, "reporting", "events", REGION));
    }

    @Test
    void conditionalDeleteRejectsStaleTokenAndUnconditionalDeleteRemainsAvailable() {
        String arn = createBucketWithNamespace("analytics");
        S3Table table = createTable(arn, "analytics", "events");
        String token = table.getVersionToken();
        service.updateTableMetadataLocation(arn, "analytics", "events", "s3://warehouse/metadata.json", token, REGION);
        assertError("ConflictException", () -> service.deleteTable(arn, "analytics", "events", token, REGION));
        assertEquals(table.getArn(), service.getTable(arn, "analytics", "events", REGION).getArn());
        service.deleteTable(arn, "analytics", "events", REGION);
        assertError("NotFoundException", () -> service.getTable(arn, "analytics", "events", REGION));
        S3Table recreated = createTable(arn, "analytics", "events");
        service.deleteTable(arn, "analytics", "events", recreated.getVersionToken(), REGION);
        assertError("NotFoundException", () -> service.getTable(arn, "analytics", "events", REGION));
    }

    private S3TablesService persistentService(Path directory) {
        StorageFactory factory = new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                    TypeReference<Map<String, V>> typeReference) {
                PersistentStorage<String, V> storage = new PersistentStorage<>(directory.resolve(fileName), typeReference);
                storage.load();
                return new AccountAwareStorageBackend<>(storage, null, ACCOUNT_ID);
            }
        };
        return new S3TablesService(factory, new RegionResolver(REGION, ACCOUNT_ID));
    }

    private TableBucket createBucket() {
        return service.createTableBucket(BUCKET, null, null, Map.of(), REGION);
    }

    private String createBucketWithNamespace(String namespace) {
        TableBucket bucket = createBucket();
        service.createNamespace(bucket.getArn(), List.of(namespace), REGION);
        return bucket.getArn();
    }

    private S3Table createTable(String arn, String namespace, String name) {
        return service.createTable(arn, namespace, name, "ICEBERG",
                Map.of("iceberg", Map.of("metadataLocation", "s3://warehouse/" + name + "/metadata/v1.json")),
                null, null, Map.of(), REGION);
    }

    private void assertError(String expectedCode, Executable action) {
        AwsException exception = assertThrows(AwsException.class, action);
        assertEquals(expectedCode, exception.getErrorCode());
    }

    @Test
    void allowsOnlyOneConcurrentMetadataUpdatePerVersionToken() throws Exception {
        String arn = createBucketWithNamespace("analytics");
        S3Table created = createTable(arn, "analytics", "events");
        String token = created.getVersionToken();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<String> first = executor.submit(() -> updateMetadataWithToken(arn, token,
                    "s3://warehouse/events/metadata/v2.json", ready, start));
            Future<String> second = executor.submit(() -> updateMetadataWithToken(arn, token,
                    "s3://warehouse/events/metadata/v3.json", ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<String> outcomes = List.of(first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS));

            assertEquals(1L, outcomes.stream().filter("updated"::equals).count());
            assertEquals(1L, outcomes.stream().filter("ConflictException"::equals).count());
        } finally {
            executor.shutdownNow();
        }
    }

    private String updateMetadataWithToken(String arn, String token, String metadataLocation,
                                           CountDownLatch ready,
                                           CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            return "timed out";
        }
        try {
            service.updateTableMetadataLocation(arn, "analytics", "events", metadataLocation, token, REGION);
            return "updated";
        } catch (AwsException exception) {
            return exception.getErrorCode();
        }
    }

}
