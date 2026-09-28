package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.ConnectionInput;
import io.github.hectorvent.floci.services.glue.model.DataCatalogEncryptionSettings;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.PartitionIndex;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.glue.model.UserDefinedFunction;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlueCatalogRegionServiceTest {

    private String region = "us-east-1";
    private final RegionResolver resolver = new RegionResolver("us-east-1", "000000000000") {
        @Override
        public String getRegion() {
            return region;
        }
    };
    private final GlueService service = new GlueService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            null, resolver, new ResourceGroupsTaggingService(null), null);

    @Test
    void catalogReadsUpdatesAndChildDeletesAreRegionLocal() {
        populate("east");
        region = "us-west-2";
        assertMissing(() -> service.getDatabase("db"));
        assertMissing(() -> service.getTable("db", "records"));
        assertMissing(() -> service.deleteDatabase("db"));
        assertMissing(() -> service.deleteTable("db", "records"));
        assertTrue(service.getDatabases().isEmpty());
        assertTrue(service.getTables("db").isEmpty());
        assertTrue(service.searchTables(null, null, null, null, null).items().isEmpty());
        assertTrue(service.getUserDefinedFunctions(null, ".*").isEmpty());

        populate("west");
        assertCatalog("west");
        service.deleteTableVersion("db", "records", "0");
        service.deletePartition("db", "records", List.of("2026"));
        service.deleteUserDefinedFunction("db", "transform");
        service.deleteColumnStatisticsForTable("db", "records", "value");
        service.getPartitionIndexes("db", "records");
        service.deletePartitionIndex("db", "records", "by_year");
        service.getPartitionIndexes("db", "records");
        assertEquals(1, service.getTableVersions("db", "records").size());
        assertTrue(service.getPartitions("db", "records").isEmpty());
        assertTrue(service.getUserDefinedFunctions(null, ".*").isEmpty());
        assertTrue(service.getPartitionIndexes("db", "records").isEmpty());
        assertTrue(service.getColumnStatisticsForTable("db", "records", List.of("value"))
                .columnStatisticsList().isEmpty());
        region = "us-east-1";
        assertCatalog("east");
    }

    @Test
    void deletingDatabaseCascadesOnlyWithinItsRegionAndDoesNotResurrectChildren() {
        populate("east");
        region = "us-west-2";
        populate("west");
        service.deleteDatabase("db");
        assertTrue(service.getDatabases().isEmpty());
        assertTrue(service.searchTables(null, null, null, null, null).items().isEmpty());
        assertTrue(service.getUserDefinedFunctions(null, ".*").isEmpty());
        service.createDatabase(new Database("db"));
        service.createTable("db", table("replacement"));
        assertEquals(1, service.getTableVersions("db", "records").size());
        assertMissing(() -> service.getUserDefinedFunction("db", "transform"));
        assertTrue(service.getPartitions("db", "records").isEmpty());
        assertTrue(service.getPartitionIndexes("db", "records").isEmpty());
        assertTrue(service.getColumnStatisticsForTable("db", "records", List.of("value"))
                .columnStatisticsList().isEmpty());
        service.createPartition("db", "records", partition("replacement"));
        assertTrue(service.getColumnStatisticsForPartition("db", "records", List.of("2026"), List.of("value"))
                .columnStatisticsList().isEmpty());
        region = "us-east-1";
        assertCatalog("east");
    }

    @Test
    void connectionsPoliciesAndEncryptionSettingsFollowTheCatalogRegion() {
        ConnectionInput connection = new ConnectionInput();
        connection.setName("network");
        connection.setConnectionType("NETWORK");
        connection.setConnectionProperties(Map.of());
        service.createConnection(connection, Map.of("region", region), region);
        String hash = service.putResourcePolicy("{\"Statement\":[]}", null, "NOT_EXIST", null);
        DataCatalogEncryptionSettings encryption = DataCatalogEncryptionSettings.defaults();
        encryption.getEncryptionAtRest().setCatalogEncryptionMode("SSE-KMS");
        service.putDataCatalogEncryptionSettings(encryption);

        region = "us-west-2";
        assertMissing(() -> service.getConnection("network", false));
        assertMissing(service::getResourcePolicy);
        assertEquals("DISABLED", service.getDataCatalogEncryptionSettings().getEncryptionAtRest().getCatalogEncryptionMode());
        service.createConnection(connection, Map.of("region", region), region);
        service.deleteConnection("network", region);

        region = "us-east-1";
        assertEquals("network", service.getConnection("network", false).getName());
        assertEquals(hash, service.getResourcePolicy().getPolicyHash());
        assertEquals("SSE-KMS", service.getDataCatalogEncryptionSettings().getEncryptionAtRest().getCatalogEncryptionMode());
        assertEquals(Map.of("region", region), service.getTags(resolver.buildArn("glue", region, "connection/network")));
    }

    private void populate(String marker) {
        Database database = new Database("db");
        database.setDescription(marker);
        service.createDatabase(database);
        service.createTable("db", table(marker + "-v0"));
        service.updateTable("db", table(marker + "-v1"), "0", false);
        service.createPartition("db", "records", partition(marker));
        PartitionIndex index = new PartitionIndex();
        index.setIndexName("by_year");
        index.setKeys(List.of("year"));
        service.createPartitionIndex("db", "records", index);
        UserDefinedFunction function = new UserDefinedFunction();
        function.setFunctionName("transform");
        function.setClassName(marker);
        service.createUserDefinedFunction("db", function);
        List<Map<String, Object>> statistics = List.of(Map.of(
                "ColumnName", "value", "ColumnType", "string", "AnalyzedTime", 1,
                "StatisticsData", Map.of("Type", "STRING", "Marker", marker)));
        service.updateColumnStatisticsForTable("db", "records", statistics);
        service.updateColumnStatisticsForPartition("db", "records", List.of("2026"), statistics);
    }

    private void assertCatalog(String marker) {
        assertEquals(marker, service.getDatabase("db").getDescription());
        assertEquals(List.of(marker), service.getDatabases().stream().map(Database::getDescription).toList());
        assertEquals(marker + "-v1", service.getTable("db", "records").getDescription());
        assertEquals(List.of(marker + "-v1"), service.getTables("db").stream().map(Table::getDescription).toList());
        assertEquals(List.of(marker + "-v1"), service.searchTables(null, null, null, null, null)
                .items().stream().map(Table::getDescription).toList());
        assertEquals(2, service.getTableVersions("db", "records").size());
        Table archived = (Table) service.getTableVersion("db", "records", "0").get("Table");
        assertEquals(marker + "-v0", archived.getDescription());
        assertEquals(marker, service.getPartition("db", "records", List.of("2026")).getParameters().get("scope"));
        assertEquals(1, service.getPartitions("db", "records").size());
        assertEquals(1, service.batchGetPartitions("db", "records", List.of(List.of("2026"))).size());
        assertEquals("by_year", service.getPartitionIndexes("db", "records").getFirst().getIndexName());
        assertEquals(marker, service.getUserDefinedFunction("db", "transform").getClassName());
        assertEquals(List.of(marker), service.getUserDefinedFunctions(null, ".*").stream()
                .map(UserDefinedFunction::getClassName).toList());
        Map<String, String> expected = Map.of("Type", "STRING", "Marker", marker);
        assertEquals(expected, service.getColumnStatisticsForTable("db", "records", List.of("value"))
                .columnStatisticsList().getFirst().get("StatisticsData"));
        assertEquals(expected, service.getColumnStatisticsForPartition("db", "records", List.of("2026"), List.of("value"))
                .columnStatisticsList().getFirst().get("StatisticsData"));
    }

    private static Table table(String description) {
        Table table = new Table();
        table.setName("records");
        table.setDescription(description);
        table.setPartitionKeys(List.of(new Column("year", "string")));
        return table;
    }

    private static Partition partition(String marker) {
        Partition partition = new Partition();
        partition.setValues(List.of("2026"));
        partition.setParameters(Map.of("scope", marker));
        return partition;
    }

    private static void assertMissing(Runnable operation) {
        assertEquals("EntityNotFoundException", assertThrows(AwsException.class, operation::run).getErrorCode());
    }
}
