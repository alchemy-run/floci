package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.BackupWindows;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerHandle;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerManager;
import io.github.hectorvent.floci.services.docdb.proxy.DocDbProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.hectorvent.floci.services.docdb.model.DocDbClusterSettings;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstanceSettings;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocDbServiceTest {

    private static final String HASH = DocDbEndpoints.hash("000000000000", "us-east-1");

    private DocDbService docDbService;
    private DocDbContainerManager containerManager;
    private RdsService rdsService;
    private Ec2Service ec2Service;
    private KmsService kmsService;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(true);

        containerManager = Mockito.mock(DocDbContainerManager.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        rdsService = Mockito.mock(RdsService.class);
        ec2Service = Mockito.mock(Ec2Service.class);
        kmsService = Mockito.mock(KmsService.class);
        when(kmsService.describeKey(any(), any())).thenThrow(new AwsException("NotFoundException", "Key not found", 404));
        when(rdsService.isManagedClusterParameterGroup(any())).thenAnswer(inv ->
                List.of("default.docdb3.6", "default.docdb4.0", "default.docdb5.0", "default.docdb8.0").contains(inv.<String>getArgument(0)));
        docDbService = new DocDbService(config, regionResolver, containerManager, storageFactory,
                rdsService, ec2Service, kmsService);
    }

    @Test
    void createClusterInMockModeSkipsContainer() {
        DocDbCluster cluster = docDbService.createDbCluster(
                "mock-cluster", null, "admin", "secret", false);

        assertNotNull(cluster);
        assertEquals("mock-cluster", cluster.getDbClusterIdentifier());
        assertEquals("available", cluster.getStatus());
        assertEquals("mock-cluster.cluster-" + HASH + ".us-east-1.docdb.localhost.floci.io", cluster.getEndpoint());
        assertEquals("mock-cluster.cluster-ro-" + HASH + ".us-east-1.docdb.localhost.floci.io",
                cluster.getReaderEndpoint());
        assertEquals(27017, cluster.getPort());
        assertTrue(cluster.getDbClusterArn().contains("mock-cluster"));

        verify(containerManager, never()).start(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt());
    }

    @Test
    void describeClusterInMockMode() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        DocDbCluster described = docDbService.getDbCluster("mock-cluster");
        assertEquals("mock-cluster", described.getDbClusterIdentifier());
        assertEquals("available", described.getStatus());
    }

    @Test
    void createInstanceInMockMode() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        DocDbInstance instance = docDbService.createDbInstance(
                "mock-instance", "mock-cluster", "db.r5.large", null, false);

        assertNotNull(instance);
        assertEquals("mock-instance", instance.getDbInstanceIdentifier());
        assertEquals("mock-cluster", instance.getDbClusterIdentifier());
        assertEquals("available", instance.getStatus());
        assertEquals("mock-instance." + HASH + ".us-east-1.docdb.localhost.floci.io", instance.getEndpoint());
        assertEquals(27017, instance.getPort());
    }

    @Test
    void listDbClustersMatchesByArnButNotForeignArn() {
        DocDbCluster cluster = docDbService.createDbCluster(
                "mock-cluster", null, "admin", "secret", false);

        String arn = cluster.getDbClusterArn();
        assertEquals(1, docDbService.listDbClusters(arn).size());
        assertTrue(docDbService.listDbClusters(arn.replace("000000000000", "999999999999")).isEmpty(),
                "cross-account ARN must not match");
        assertTrue(docDbService.listDbClusters(arn.replace("us-east-1", "eu-west-1")).isEmpty(),
                "cross-region ARN must not match");
    }

    @Test
    void listDbInstancesMatchesByArnButNotForeignArn() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);
        DocDbInstance instance = docDbService.createDbInstance(
                "mock-instance", "mock-cluster", "db.r5.large", null, false);

        String arn = instance.getDbInstanceArn();
        assertEquals(1, docDbService.listDbInstances(arn).size());
        assertTrue(docDbService.listDbInstances(arn.replace("000000000000", "999999999999")).isEmpty(),
                "cross-account ARN must not match");
        assertTrue(docDbService.listDbInstances(arn.replace("us-east-1", "eu-west-1")).isEmpty(),
                "cross-region ARN must not match");
    }

    @Test
    void deleteClusterInMockModeSkipsContainerStop() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        docDbService.deleteDbCluster("mock-cluster");

        assertTrue(docDbService.listDbClusters(null).isEmpty());
        verify(containerManager, never()).stop(any());
    }

    @Test
    void createWithoutDockerDaemonStillReachesAvailable() {
        // tryStart() returns null when no Docker daemon is reachable. The cluster record is
        // metadata, so the create still succeeds and the cluster reaches 'available' on the
        // first describe (what SDK/Terraform waiters poll).
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(false);
        when(docdbConfig.defaultImage()).thenReturn("mongo:7.0");
        when(config.hostname()).thenReturn(java.util.Optional.of("localhost"));

        DocDbContainerManager noDaemonContainerManager = Mockito.mock(DocDbContainerManager.class);
        when(noDaemonContainerManager.tryStart(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt()))
                .thenReturn(null);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        DocDbService noDaemonService = new DocDbService(config, regionResolver, noDaemonContainerManager, storageFactory,
                rdsService, ec2Service, kmsService);

        DocDbCluster created = noDaemonService.createDbCluster(
                "no-docker-cluster", null, "admin", "secret", false);

        assertEquals("available", created.getStatus());
        assertEquals("no-docker-cluster.cluster-" + HASH + ".us-east-1.docdb.localhost", created.getEndpoint());
        assertEquals(27017, created.getPort());

        assertEquals("no-docker-cluster",
                noDaemonService.getDbCluster("no-docker-cluster").getDbClusterIdentifier());

        // Delete must not reach for a container that was never created.
        noDaemonService.deleteDbCluster("no-docker-cluster");
        verify(noDaemonContainerManager, never()).stop(any());
    }

    @Test
    void tagsAreAnsweredOnlyForAnArnInThisRegionAndAccount() {
        // The router reaches this service only for an ARN it already matched, so these checks
        // guard the service's own contract: storage is keyed by identifier alone, and answering
        // by name would hand this caller's cluster to an ARN naming somewhere else. The message
        // is the one a live account gives, which is the same for both cases.
        docDbService.createDbCluster("scoped-cluster", null, "admin", "secret", false);
        String arn = docDbService.getDbCluster("scoped-cluster").getDbClusterArn();
        docDbService.addTagsToResource(arn, java.util.Map.of("env", "test"));
        assertEquals(java.util.Map.of("env", "test"), docDbService.listTagsForResource(arn));

        for (String foreign : new String[]{
                "arn:aws:rds:eu-west-1:000000000000:cluster:scoped-cluster",
                "arn:aws:rds:us-east-1:111122223333:cluster:scoped-cluster"}) {
            AwsException rejected = assertThrows(AwsException.class,
                    () -> docDbService.listTagsForResource(foreign));
            assertEquals("InvalidParameterValue", rejected.getErrorCode());
            assertTrue(rejected.getMessage().contains("does not match an RDS resource in this region"));
        }

        AwsException notAnArn = assertThrows(AwsException.class,
                () -> docDbService.listTagsForResource("scoped-cluster"));
        assertTrue(notAnArn.getMessage().contains("Invalid resource name"));
    }

    @Test
    void aRecordStoredUnderABareIdentifierIsFoundAndMovedUnderItsRegionalKey() {
        // The upgrade path: records written before regions were part of the key sit under the
        // bare identifier. They belong to the default region — that is the region they were
        // created under — and are moved there on the first read, so the bare key is consulted
        // once rather than for ever.
        StorageBackend<String, DocDbCluster> clusterStore =
                AccountAwareStorageBackend.inMemory("000000000000");
        StorageBackend<String, DocDbInstance> instanceStore =
                AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                "docdb-clusters.json".equals(inv.getArgument(1)) ? clusterStore : instanceStore);

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(true);
        DocDbService service = new DocDbService(config,
                new RegionResolver("us-east-1", "000000000000"),
                Mockito.mock(DocDbContainerManager.class), storageFactory,
                Mockito.mock(RdsService.class), Mockito.mock(Ec2Service.class), Mockito.mock(KmsService.class));

        DocDbCluster legacy = new DocDbCluster();
        legacy.setDbClusterIdentifier("bare-key-cluster");
        legacy.setStatus("available");
        clusterStore.put("bare-key-cluster", legacy);

        assertEquals("available", service.getDbCluster("bare-key-cluster").getStatus());
        assertTrue(clusterStore.get("us-east-1::bare-key-cluster").isPresent(),
                "the record should have been moved under its regional key");
        assertTrue(clusterStore.get("bare-key-cluster").isEmpty(),
                "the bare key should not be left behind");

        // It is the default region's, and only that region's.
        assertTrue(service.hasCluster("bare-key-cluster", "us-east-1"));
        assertFalse(service.hasCluster("bare-key-cluster", "eu-west-1"));

        // And it lists there, once rather than twice.
        assertEquals(1, service.listDbClusters(null).size());
    }

    private static final String KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/k1";

    private void knownReferences() {
        when(rdsService.getDbSubnetGroup(eq("sng"), any())).thenReturn(new DbSubnetGroup());
        when(rdsService.getDbSubnetGroup(eq("nope"), any())).thenThrow(new AwsException("DBSubnetGroupNotFoundFault", "x", 404));
        when(rdsService.getDbClusterParameterGroup(eq("pg"), any())).thenReturn(new DbClusterParameterGroup("pg", "docdb5.0", "d"));
        when(rdsService.getDbClusterParameterGroup(eq("nope"), any())).thenThrow(new AwsException("DBClusterParameterGroupNotFound", "x", 404));
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any())).thenReturn(List.of(sg));
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-nope")), any(), any())).thenReturn(List.of());
        KmsKey key = new KmsKey();
        key.setKeyId("k1");
        key.setArn(KEY_ARN);
        key.setEnabled(true);
        key.setKeyState("Enabled");
        Mockito.doReturn(key).when(kmsService).describeKey("alias/docs", "us-east-1");
        Mockito.doReturn(key).when(kmsService).describeKey(KEY_ARN, "us-east-1");
    }

    @Test
    void createDbClusterStoresPlacementEncryptionBackupSettingsAndTags() {
        knownReferences();
        DocDbCluster created = docDbService.createDbCluster("c1", "5.0.0", "u", "pw", false,
                new DocDbClusterSettings("sng", "pg", List.of("sg-1"), true, "alias/docs", 5,
                        "23:30-00:00", "Sun:03:00-Sun:04:00", true),
                Map.of("Name", "c1"));

        DocDbCluster stored = docDbService.getDbCluster("c1");
        assertEquals("sng", stored.getDbSubnetGroupName());
        assertEquals("pg", stored.getDbClusterParameterGroupName());
        assertEquals(List.of("sg-1"), stored.getVpcSecurityGroupIds());
        assertTrue(stored.isStorageEncrypted());
        assertEquals(KEY_ARN, stored.getKmsKeyId());
        assertEquals(5, stored.getBackupRetentionPeriod());
        assertEquals("23:30-00:00", stored.getPreferredBackupWindow());
        assertEquals("sun:03:00-sun:04:00", stored.getPreferredMaintenanceWindow());
        assertTrue(stored.isDeletionProtection());
        assertEquals(Map.of("Name", "c1"), stored.getTags());
        assertEquals(created.getDbClusterArn(), stored.getDbClusterArn());
    }

    @Test
    void createDbClusterWithoutSettingsTakesAwsDefaults() {
        docDbService.createDbCluster("c1", "5.0.0", "u", "pw", false);
        DocDbCluster stored = docDbService.getDbCluster("c1");
        assertEquals("default", stored.getDbSubnetGroupName());
        assertEquals("default.docdb5.0", stored.getDbClusterParameterGroupName());
        assertEquals(List.of(Ec2Service.defaultSecurityGroupId("us-east-1")), stored.getVpcSecurityGroupIds());
        assertFalse(stored.isStorageEncrypted());
        assertNull(stored.getKmsKeyId());
        assertEquals(1, stored.getBackupRetentionPeriod());
        assertEquals("04:00-06:00", stored.getPreferredBackupWindow());
        assertEquals("mon:00:00-mon:03:00", stored.getPreferredMaintenanceWindow());
        assertFalse(stored.isDeletionProtection());
    }

    @Test
    void recordsPersistedBeforeSettingsWereStoredReadBackWithTheAwsDefaults() {
        StorageBackend<String, DocDbCluster> clusterStore = AccountAwareStorageBackend.inMemory("000000000000");
        StorageBackend<String, DocDbInstance> instanceStore = AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                "docdb-clusters.json".equals(inv.getArgument(1)) ? clusterStore : instanceStore);
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(true);
        DocDbService service = new DocDbService(config, new RegionResolver("us-east-1", "000000000000"),
                containerManager, storageFactory, rdsService, ec2Service, kmsService);

        DocDbCluster legacy = new DocDbCluster();
        legacy.setDbClusterIdentifier("old");
        legacy.setEngineVersion("4.0.0");
        legacy.setDbClusterArn("arn:aws:rds:us-east-1:000000000000:cluster:old");
        clusterStore.put("us-east-1::old", legacy);
        DocDbInstance legacyInstance = new DocDbInstance();
        legacyInstance.setDbInstanceIdentifier("old-1");
        legacyInstance.setDbClusterIdentifier("old");
        legacyInstance.setDbInstanceArn("arn:aws:rds:us-east-1:000000000000:db:old-1");
        instanceStore.put("us-east-1::old-1", legacyInstance);

        for (DocDbCluster read : List.of(service.getDbCluster("old"),
                service.listDbClusters(null).iterator().next(),
                service.listDbClusters("arn:aws:rds:us-east-1:000000000000:cluster:old").iterator().next())) {
            assertEquals("default", read.getDbSubnetGroupName());
            assertEquals("default.docdb4.0", read.getDbClusterParameterGroupName());
            assertEquals(List.of(Ec2Service.defaultSecurityGroupId("us-east-1")), read.getVpcSecurityGroupIds());
            assertEquals(1, read.getBackupRetentionPeriod());
            assertEquals("04:00-06:00", read.getPreferredBackupWindow());
            assertEquals("mon:00:00-mon:03:00", read.getPreferredMaintenanceWindow());
            assertFalse(read.isStorageEncrypted());
            assertFalse(read.isDeletionProtection());
        }
        assertEquals("default", clusterStore.get("us-east-1::old").orElseThrow().getDbSubnetGroupName());

        DocDbInstance readInstance = service.getDbInstance("old-1");
        assertEquals("mon:00:00-mon:03:00", readInstance.getPreferredMaintenanceWindow());
        assertTrue(readInstance.isAutoMinorVersionUpgrade());
        assertEquals(1, readInstance.getPromotionTier());
        assertEquals("mon:00:00-mon:03:00",
                instanceStore.get("us-east-1::old-1").orElseThrow().getPreferredMaintenanceWindow());
    }

    @Test
    void deleteDbClusterRefusesAProtectedClusterUntilProtectionIsTurnedOff() {
        docDbService.createDbCluster("c1", "5.0.0", "u", "pw", false,
                new DocDbClusterSettings(null, null, null, null, null, null, null, null, true), Map.of());
        AwsException e = assertThrows(AwsException.class, () -> docDbService.deleteDbCluster("c1"));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        assertEquals("Cannot delete protected Cluster, please disable deletion protection and try again.", e.getMessage());
        assertEquals("available", docDbService.getDbCluster("c1").getStatus());

        docDbService.modifyDbCluster("c1", null, null,
                new DocDbClusterSettings(null, null, null, null, null, null, null, null, false));
        docDbService.deleteDbCluster("c1");
        assertThrows(AwsException.class, () -> docDbService.getDbCluster("c1"));
    }

    @Test
    void parameterGroupMustBelongToTheEngineVersionTheClusterWillRun() {
        knownReferences();
        when(rdsService.getDbClusterParameterGroup(eq("pg4"), any()))
                .thenReturn(new DbClusterParameterGroup("pg4", "docdb4.0", "d"));
        String expected = "The Parameter Group pg4 with DBParameterGroupFamily docdb4.0 cannot be used for this "
                + "instance. Please use a Parameter Group with DBParameterGroupFamily docdb5.0";

        AwsException e = refused(new DocDbClusterSettings(null, "pg4", null, null, null, null, null, null, null));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        assertEquals(expected, e.getMessage());

        docDbService.createDbCluster("c1", "5.0.0", "u", "pw", false);
        e = assertThrows(AwsException.class, () -> docDbService.modifyDbCluster("c1", null, null,
                new DocDbClusterSettings(null, "pg4", null, null, null, null, null, null, null)));
        assertEquals(expected, e.getMessage());
        assertEquals("default.docdb5.0", docDbService.getDbCluster("c1").getDbClusterParameterGroupName());

        // judged against the version the cluster will have, not the one it has
        e = assertThrows(AwsException.class, () -> docDbService.modifyDbCluster("c1", "4.0.0", null,
                new DocDbClusterSettings(null, "pg", null, null, null, null, null, null, null)));
        assertEquals("The Parameter Group pg with DBParameterGroupFamily docdb5.0 cannot be used for this "
                + "instance. Please use a Parameter Group with DBParameterGroupFamily docdb4.0", e.getMessage());
        assertEquals("5.0.0", docDbService.getDbCluster("c1").getEngineVersion());

        docDbService.modifyDbCluster("c1", "4.0.0", null, new DocDbClusterSettings(null, "pg4", null, null, null, null, null, null, null));
        assertEquals("pg4", docDbService.getDbCluster("c1").getDbClusterParameterGroupName());

        // a custom group of the old family is not carried across an engine version change
        e = assertThrows(AwsException.class, () -> docDbService.modifyDbCluster("c1", "5.0.0", null));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        assertEquals("The current DB cluster parameter group pg4 is custom. You must explicitly specify a new "
                + "DB cluster parameter group, either default or custom, for the engine version upgrade.", e.getMessage());
        assertEquals("4.0.0", docDbService.getDbCluster("c1").getEngineVersion());
        docDbService.modifyDbCluster("c1", "5.0.0", null, new DocDbClusterSettings(null, "pg", null, null, null, null, null, null, null));
        assertEquals("pg", docDbService.getDbCluster("c1").getDbClusterParameterGroupName());
        assertEquals("5.0.0", docDbService.getDbCluster("c1").getEngineVersion());

        // the group's stored family decides, whatever its name says
        when(rdsService.getDbClusterParameterGroup(eq("mine-docdb5.0"), any()))
                .thenReturn(new DbClusterParameterGroup("mine-docdb5.0", "docdb4.0", "d"));
        docDbService.createDbCluster("c3", "4.0.0", "u", "pw", false,
                new DocDbClusterSettings(null, "mine-docdb5.0", null, null, null, null, null, null, null), Map.of());
        e = assertThrows(AwsException.class, () -> docDbService.modifyDbCluster("c3", "5.0.0", null));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        assertEquals("4.0.0", docDbService.getDbCluster("c3").getEngineVersion());

        // a custom group is not a default one because of its name
        when(rdsService.getDbClusterParameterGroup(eq("default.mine"), any()))
                .thenReturn(new DbClusterParameterGroup("default.mine", "docdb4.0", "d"));
        docDbService.createDbCluster("c4", "4.0.0", "u", "pw", false,
                new DocDbClusterSettings(null, "default.mine", null, null, null, null, null, null, null), Map.of());
        e = assertThrows(AwsException.class, () -> docDbService.modifyDbCluster("c4", "5.0.0", null));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        assertEquals("default.mine", docDbService.getDbCluster("c4").getDbClusterParameterGroupName());

        // a cluster on a default group follows the engine version to the new family's default
        docDbService.createDbCluster("c2", "4.0.0", "u", "pw", false);
        docDbService.modifyDbCluster("c2", "5.0.0", null);
        assertEquals("default.docdb5.0", docDbService.getDbCluster("c2").getDbClusterParameterGroupName());
    }

    @Test
    void aWindowThatLeavesNoRoomForTheDerivedOneIsRefused() {
        AwsException e = refused(new DocDbClusterSettings(null, null, null, null, null, null, "00:00-23:45", null, null));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("The specified backup window overlaps all available default maintenance windows. "
                + "Shrink the backup window or specify a non-overlapping maintenance window.", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, null, null, null, null, "23:00-22:59", null, null));
        assertEquals("The specified backup window overlaps all available default maintenance windows. "
                + "Shrink the backup window or specify a non-overlapping maintenance window.", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, null, null, null, null, null, "mon:00:00-mon:23:45", null));
        assertEquals("The specified maintenance window overlaps all available default backup windows. "
                + "Shrink the maintenance window or specify a non-overlapping backup window.", e.getMessage());

        docDbService.createDbCluster("c1", "5.0.0", "u", "pw", false,
                new DocDbClusterSettings(null, null, null, null, null, null, "22:00-23:50", null, null), Map.of());
        DocDbCluster stored = docDbService.getDbCluster("c1");
        assertEquals("22:00-23:50", stored.getPreferredBackupWindow());
        assertFalse(BackupWindows.overlap(stored.getPreferredBackupWindow(), stored.getPreferredMaintenanceWindow()));
    }

    @Test
    void engineVersionsOutsideTheCatalogueAreRefusedBeforeAnythingChanges() {
        for (String version : List.of("9.9.9", "5", "abc", "5.0.2")) {
            AwsException e = assertThrows(AwsException.class, () -> docDbService.createDbCluster(
                    "c1", version, "u", "pw", false, DocDbClusterSettings.unchanged(), Map.of()));
            assertEquals("InvalidParameterCombination", e.getErrorCode());
            assertEquals("Cannot find version " + version + " for docdb", e.getMessage());
            assertThrows(AwsException.class, () -> docDbService.getDbCluster("c1"));
        }

        docDbService.createDbCluster("c1", "8.0.1", "u", "pw", false);
        assertEquals("default.docdb8.0", docDbService.getDbCluster("c1").getDbClusterParameterGroupName());
        docDbService.createDbCluster("c2", "5.0", "u", "pw", false);
        assertEquals("5.0", docDbService.getDbCluster("c2").getEngineVersion());
        assertEquals("default.docdb5.0", docDbService.getDbCluster("c2").getDbClusterParameterGroupName());
        docDbService.createDbCluster("c3", null, "u", "pw", false);
        assertEquals("5.0.0", docDbService.getDbCluster("c3").getEngineVersion());

        AwsException e = assertThrows(AwsException.class, () -> docDbService.modifyDbCluster("c1", "9.9.9", null));
        assertEquals("Cannot find version 9.9.9 for docdb", e.getMessage());
        assertEquals("8.0.1", docDbService.getDbCluster("c1").getEngineVersion());
        assertEquals("default.docdb8.0", docDbService.getDbCluster("c1").getDbClusterParameterGroupName());
    }

    private AwsException refused(DocDbClusterSettings settings) {
        return assertThrows(AwsException.class, () -> docDbService.createDbCluster(
                "c1", "5.0.0", "u", "pw", false, settings, Map.of()));
    }

    @Test
    void createDbClusterRefusesWhatALiveAccountRefusesBeforeStartingAContainer() {
        knownReferences();
        AwsException e;
        e = refused(new DocDbClusterSettings(null, null, null, false, KEY_ARN, null, null, null, null));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        assertEquals("You cannot specify KMS key for unencrypted clusters.", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, null, null, KEY_ARN, null, null, null, null));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        e = refused(new DocDbClusterSettings(null, null, null, true, "alias/nope", null, null, null, null));
        assertEquals("KMSKeyNotAccessibleFault", e.getErrorCode());
        assertEquals("The specified KMS key [alias/nope] does not exist, is not enabled or you do not have permissions to access it.", e.getMessage());
        e = refused(new DocDbClusterSettings("nope", null, null, null, null, null, null, null, null));
        assertEquals("DBSubnetGroupNotFoundFault", e.getErrorCode());
        assertEquals("DB subnet group 'nope' does not exist.", e.getMessage());
        e = refused(new DocDbClusterSettings(null, "nope", null, null, null, null, null, null, null));
        assertEquals("DBClusterParameterGroupNotFound", e.getErrorCode());
        assertEquals("DBClusterParameterGroup not found: nope", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, List.of("sg-nope"), null, null, null, null, null, null));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("Invalid security group , groupId= sg-nope, groupName=.", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, null, null, null, 0, null, null, null));
        assertEquals("Invalid backup retention period: 0. Retention period must be between 1 and 354.", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, null, null, null, null, "25:00-26:00", null, null));
        assertEquals("Invalid backup window time '25:00' specified. Should be specified as a time hh24:mi (24H Clock UTC). Example: 03:15", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, null, null, null, null, "02:00-02:10", null, null));
        assertEquals("Backup window must be at least 30 minutes.", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, null, null, null, null, null, "xxx:00:00-xxx:01:00", null));
        assertEquals("Invalid maintenance window time 'xxx:00:00' specified. Should be specified as a time ddd:hh24:mi (24H Clock UTC). Example: Mon:00:15", e.getMessage());
        e = refused(new DocDbClusterSettings(null, null, null, null, null, null, "02:00-02:30", "tue:02:15-tue:02:45", null));
        assertEquals("The backup window and maintenance window must not overlap.", e.getMessage());
        assertThrows(AwsException.class, () -> docDbService.getDbCluster("c1"));
        verify(containerManager, never()).tryStart(any(), any(), any(), any(), any(), anyInt());

        // a window given alone is paired with a default clear of it
        docDbService.createDbCluster("alone", "5.0.0", "u", "pw", false,
                new DocDbClusterSettings(null, null, null, null, null, null, "00:30-01:00", null, null), Map.of());
        assertEquals("mon:01:00-mon:01:30", docDbService.getDbCluster("alone").getPreferredMaintenanceWindow());
    }

    @Test
    void modifyDbClusterAppliesGivenSettingsChecksTheStoredWindowAndChangesNothingWhenRefused() {
        knownReferences();
        docDbService.createDbCluster("c1", "5.0.0", "u", "pw", false,
                new DocDbClusterSettings(null, null, null, true, KEY_ARN, 5, "01:00-01:30", "thu:10:00-thu:10:30", null),
                Map.of());

        docDbService.modifyDbCluster("c1", null, null,
                new DocDbClusterSettings(null, "pg", List.of("sg-1"), null, null, 7, "02:00-02:30", "wed:05:00-wed:06:00", true));
        DocDbCluster stored = docDbService.getDbCluster("c1");
        assertEquals(7, stored.getBackupRetentionPeriod());
        assertEquals("02:00-02:30", stored.getPreferredBackupWindow());
        assertEquals("wed:05:00-wed:06:00", stored.getPreferredMaintenanceWindow());
        assertEquals("pg", stored.getDbClusterParameterGroupName());
        assertEquals(List.of("sg-1"), stored.getVpcSecurityGroupIds());
        assertTrue(stored.isDeletionProtection());
        assertTrue(stored.isStorageEncrypted());
        assertEquals(KEY_ARN, stored.getKmsKeyId());

        AwsException overlap = assertThrows(AwsException.class, () -> docDbService.modifyDbCluster("c1", null, null,
                new DocDbClusterSettings(null, null, null, null, null, null, null, "mon:02:15-mon:02:45", null)));
        assertEquals("The backup window and maintenance window must not overlap.", overlap.getMessage());
        AwsException badPg = assertThrows(AwsException.class, () -> docDbService.modifyDbCluster("c1", null, null,
                new DocDbClusterSettings(null, "nope", null, null, null, 9, null, null, null)));
        assertEquals("DBClusterParameterGroupNotFound", badPg.getErrorCode());
        assertEquals(7, docDbService.getDbCluster("c1").getBackupRetentionPeriod());
        assertEquals("wed:05:00-wed:06:00", docDbService.getDbCluster("c1").getPreferredMaintenanceWindow());
    }

    @Test
    void createDbInstanceStoresSettingsAndTagsAndTakesTheClustersMaintenanceWindow() {
        knownReferences();
        docDbService.createDbCluster("c1", "5.0.0", "u", "pw", false,
                new DocDbClusterSettings(null, null, null, null, null, null, null, "thu:10:00-thu:10:30", null), Map.of());
        docDbService.createDbInstance("i1", "c1", "db.t3.medium", null, false,
                new DocDbInstanceSettings(false, "mon:05:00-mon:06:00", true, 2), Map.of("Name", "i1"));
        docDbService.createDbInstance("i2", "c1", "db.t3.medium", null, false);

        DocDbInstance i1 = docDbService.getDbInstance("i1");
        assertFalse(i1.isAutoMinorVersionUpgrade());
        assertEquals("mon:05:00-mon:06:00", i1.getPreferredMaintenanceWindow());
        assertTrue(i1.isCopyTagsToSnapshot());
        assertEquals(2, i1.getPromotionTier());
        assertEquals(Map.of("Name", "i1"), i1.getTags());
        DocDbInstance i2 = docDbService.getDbInstance("i2");
        assertTrue(i2.isAutoMinorVersionUpgrade());
        assertEquals("thu:10:00-thu:10:30", i2.getPreferredMaintenanceWindow());
        assertFalse(i2.isCopyTagsToSnapshot());
        assertEquals(1, i2.getPromotionTier());

        docDbService.modifyDbInstance("i2", null, null, new DocDbInstanceSettings(false, null, true, null));
        assertFalse(docDbService.getDbInstance("i2").isAutoMinorVersionUpgrade());
        assertTrue(docDbService.getDbInstance("i2").isCopyTagsToSnapshot());
        assertEquals("thu:10:00-thu:10:30", docDbService.getDbInstance("i2").getPreferredMaintenanceWindow());
    }

    private DocDbProxyManager proxyManager;
    private SecretsManagerService secretsManagerService;

    /** A service in container mode whose Docker, listeners and Secrets Manager are mocks. */
    private DocDbService containerModeService(DocDbContainerManager manager) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(false);
        when(docdbConfig.defaultImage()).thenReturn("mongo:7.0");
        proxyManager = Mockito.mock(DocDbProxyManager.class);
        when(proxyManager.reserve(anyString(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1) != null ? inv.<Integer>getArgument(1) : 27017);
        secretsManagerService = Mockito.mock(SecretsManagerService.class);
        when(secretsManagerService.createSecret(anyString(), anyString(), any(), anyString(), any(), anyList(),
                anyString(), anyString())).thenAnswer(inv -> {
                    Secret secret = new Secret();
                    secret.setArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:"
                            + inv.getArgument(0) + "-AbCdEf");
                    return secret;
                });
        return new DocDbService(config, new RegionResolver("us-east-1", "000000000000"), manager, proxyManager,
                storageFactory, rdsService, ec2Service, kmsService, secretsManagerService);
    }

    /** Containers that start, each reachable on its own loopback port from 40000. */
    private static DocDbContainerManager runningContainers() {
        DocDbContainerManager manager = Mockito.mock(DocDbContainerManager.class);
        AtomicInteger nextPort = new AtomicInteger(40000);
        when(manager.tryStart(anyString(), anyString(), any(), any(), anyString(), anyInt()))
                .thenAnswer(inv -> new DocDbContainerHandle("cid-" + inv.getArgument(0), inv.getArgument(0),
                        "127.0.0.1", nextPort.getAndIncrement()));
        return manager;
    }

    @Test
    void containerModeServesEveryClusterOnTheDocumentDbDefaultPortUnderItsOwnHostname() {
        DocDbContainerManager manager = runningContainers();
        DocDbService service = containerModeService(manager);

        DocDbCluster first = service.createDbCluster("docs", null, "alchemy", "pw", false);
        DocDbCluster second = service.createDbCluster("more-docs", null, "alchemy", "pw", false);

        String endpoint = "docs.cluster-" + HASH + ".us-east-1.docdb.localhost.floci.io";
        String reader = "docs.cluster-ro-" + HASH + ".us-east-1.docdb.localhost.floci.io";
        assertEquals(endpoint, first.getEndpoint());
        assertEquals(27017, first.getPort());
        assertEquals(27017, second.getPort());
        verify(proxyManager).reserve(first.getDbClusterArn(), null, true);
        // the replica set advertises the endpoint clients are given, on the port they are given
        verify(manager).tryStart("docs", "mongo:7.0", "alchemy", "pw", endpoint, 27017);
        verify(proxyManager).attach(first.getDbClusterArn(), List.of(endpoint, reader), "127.0.0.1", 40000);
        verify(proxyManager).attach(eq(second.getDbClusterArn()), anyList(), eq("127.0.0.1"), eq(40001));

        DocDbInstance instance = service.createDbInstance("writer", "docs", "db.t3.medium", null, false);
        String instanceEndpoint = "writer." + HASH + ".us-east-1.docdb.localhost.floci.io";
        assertEquals(instanceEndpoint, instance.getEndpoint());
        assertEquals(27017, instance.getPort());
        verify(proxyManager).addHostname(first.getDbClusterArn(), instanceEndpoint);

        service.deleteDbInstance("writer");
        verify(proxyManager).removeHostname(first.getDbClusterArn(), instanceEndpoint);
        service.deleteDbCluster("docs");
        verify(proxyManager).release(first.getDbClusterArn());
        verify(manager).stop(any());
    }

    @Test
    void containerModePassesTheRequestedPortOnAndRefusesOneAwsWouldRefuse() {
        DocDbContainerManager manager = runningContainers();
        DocDbService service = containerModeService(manager);

        DocDbCluster chosen = service.createDbCluster("chosen", null, "u", "pw", false,
                DocDbClusterSettings.defaults(), Map.of(), 28000, false, null);
        assertEquals(28000, chosen.getPort());
        verify(manager).tryStart(eq("chosen"), anyString(), any(), any(), anyString(), eq(28000));

        AwsException invalid = assertThrows(AwsException.class, () -> service.createDbCluster("low", null, "u",
                "pw", false, DocDbClusterSettings.defaults(), Map.of(), 80, false, null));
        assertEquals("InvalidParameterValue", invalid.getErrorCode());
        verify(proxyManager, never()).reserve(anyString(), eq(80), anyBoolean());
    }

    @Test
    void clusterWithTlsDisabledInItsParameterGroupIsServedWithoutTls() {
        DocDbContainerManager manager = runningContainers();
        DocDbService service = containerModeService(manager);
        DbClusterParameterGroup group = new DbClusterParameterGroup("no-tls", "docdb5.0", "d");
        group.getParameters().put("tls", "disabled");
        when(rdsService.getDbClusterParameterGroup(eq("no-tls"), any())).thenReturn(group);

        DocDbCluster cluster = service.createDbCluster("plain", "5.0.0", "u", "pw", false,
                new DocDbClusterSettings(null, "no-tls", null, null, null, null, null, null, null), Map.of());

        verify(proxyManager).reserve(cluster.getDbClusterArn(), null, false);
    }

    @Test
    void manageMasterUserPasswordKeepsAGeneratedPasswordInAManagedSecret() {
        DocDbContainerManager manager = runningContainers();
        DocDbService service = containerModeService(manager);

        DocDbCluster cluster = service.createDbCluster("docs", null, "alchemy", null, false,
                DocDbClusterSettings.defaults(), Map.of(), null, true, null);

        String secretName = "rds!" + cluster.getDbClusterResourceId().toLowerCase(Locale.ROOT);
        String secretArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:" + secretName + "-AbCdEf";
        assertEquals(secretArn, cluster.getMasterUserSecretArn());
        assertEquals("active", cluster.getMasterUserSecretStatus());
        assertNull(cluster.getMasterUserSecretKmsKeyId());

        ArgumentCaptor<String> secretString = ArgumentCaptor.forClass(String.class);
        verify(secretsManagerService).createSecret(eq(secretName), secretString.capture(), any(), anyString(),
                any(), anyList(), eq("rds"), eq("us-east-1"));
        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        verify(manager).tryStart(eq("docs"), anyString(), eq("alchemy"), password.capture(), anyString(), anyInt());
        assertTrue(password.getValue() != null && !password.getValue().isBlank());
        assertEquals("{\"username\":\"alchemy\",\"password\":\"" + password.getValue() + "\"}",
                secretString.getValue(), "the database's master user must be able to sign in with the secret");

        service.deleteDbCluster("docs");
        verify(secretsManagerService).deleteSecret(secretArn, null, true, "us-east-1");
    }

    @Test
    void manageMasterUserPasswordRefusesAPasswordAndAKeyWithoutIt() {
        DocDbService service = containerModeService(runningContainers());

        AwsException both = assertThrows(AwsException.class, () -> service.createDbCluster("docs", null, "u",
                "pw", false, DocDbClusterSettings.defaults(), Map.of(), null, true, null));
        assertEquals("InvalidParameterCombination", both.getErrorCode());
        AwsException keyOnly = assertThrows(AwsException.class, () -> service.createDbCluster("docs", null, "u",
                "pw", false, DocDbClusterSettings.defaults(), Map.of(), null, false, "alias/k"));
        assertEquals("InvalidParameterCombination", keyOnly.getErrorCode());
        assertFalse(service.hasCluster("docs"));
        verify(secretsManagerService, never()).createSecret(anyString(), anyString(), any(), anyString(), any(),
                anyList(), anyString(), anyString());
    }

    @Test
    void aFailedBackendStartLeavesNoSecretContainerOrPortBehind() {
        DocDbContainerManager manager = runningContainers();
        DocDbService service = containerModeService(manager);
        Mockito.doThrow(new IllegalStateException("listener gone")).when(proxyManager)
                .attach(anyString(), anyList(), anyString(), anyInt());

        assertThrows(IllegalStateException.class, () -> service.createDbCluster("docs", null, "u", null, false,
                DocDbClusterSettings.defaults(), Map.of(), null, true, null));

        assertFalse(service.hasCluster("docs"));
        verify(manager).stop(any());
        verify(proxyManager).release(anyString());
        verify(secretsManagerService).deleteSecret(anyString(), any(), eq(true), eq("us-east-1"));
    }

    @Test
    void noDockerDaemonReleasesTheReservedPort() {
        DocDbContainerManager manager = Mockito.mock(DocDbContainerManager.class);
        DocDbService service = containerModeService(manager);

        DocDbCluster cluster = service.createDbCluster("docs", null, "u", "pw", false);

        assertEquals(27017, cluster.getPort());
        verify(proxyManager).release(cluster.getDbClusterArn());
        verify(proxyManager, never()).attach(anyString(), anyList(), anyString(), anyInt());
    }
}
