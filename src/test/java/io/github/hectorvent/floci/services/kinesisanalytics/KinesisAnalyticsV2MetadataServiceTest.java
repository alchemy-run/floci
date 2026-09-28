package io.github.hectorvent.floci.services.kinesisanalytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesisanalytics.container.FlinkContainerManager;
import io.github.hectorvent.floci.services.kinesisanalytics.model.ApplicationStatus;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KinesisAnalyticsV2MetadataServiceTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STREAM = "arn:aws:logs:us-east-1:000000000000:log-group:app:log-stream:errors";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<KinesisAnalyticsV2Service> services = new ArrayList<>();
    private final FlinkContainerManager containers = mock(FlinkContainerManager.class);
    @TempDir Path directory;

    @AfterEach
    void closeServices() {
        services.forEach(KinesisAnalyticsV2Service::shutdown);
    }

    private KinesisAnalyticsV2Service service(String account, String region) {
        PersistentStorage<String, FlinkApplication> persistent = new PersistentStorage<>(directory.resolve("apps.json"),
                new TypeReference<Map<String, FlinkApplication>>() {});
        persistent.load();
        StorageFactory factory = mock(StorageFactory.class);
        doReturn(new AccountAwareStorageBackend<>(persistent, null, account))
                .when(factory).create(anyString(), anyString(), any());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultRegion()).thenReturn(REGION);
        when(config.services().kinesisAnalytics().mock()).thenReturn(false);
        KinesisAnalyticsV2Service service = new KinesisAnalyticsV2Service(factory, config,
                new RegionResolver(region, account), containers);
        services.add(service);
        return service;
    }

    private ObjectNode call(KinesisAnalyticsV2Service service, String action, String json) throws Exception {
        return (ObjectNode) new KinesisAnalyticsV2JsonHandler(service, mapper)
                .handle(action, mapper.readTree(json), REGION).getEntity();
    }

    private ObjectNode create(KinesisAnalyticsV2Service service) throws Exception {
        return (ObjectNode) call(service, "CreateApplication", """
                {"ApplicationName":"metadata","RuntimeEnvironment":"FLINK-1_20",
                 "ServiceExecutionRole":"arn:aws:iam::000000000000:role/flink",
                 "ApplicationConfiguration":{
                   "EnvironmentProperties":{"PropertyGroups":[{"PropertyGroupId":"app","PropertyMap":{"mode":"test"}}]},
                   "FlinkApplicationConfiguration":{"ParallelismConfiguration":{
                     "ConfigurationType":"CUSTOM","Parallelism":2,"ParallelismPerKPU":1,"AutoScalingEnabled":false}}}}
                """).get("ApplicationDetail");
    }

    @Test
    void configurationVersionsOperationsAndMaintenanceSurviveDiskReload() throws Exception {
        KinesisAnalyticsV2Service first = service(ACCOUNT, REGION);
        create(first);
        call(first, "UpdateApplication", """
                {"ApplicationName":"metadata","CurrentApplicationVersionId":1,
                 "ApplicationConfigurationUpdate":{
                   "EnvironmentPropertyUpdates":{"PropertyGroups":[{"PropertyGroupId":"app","PropertyMap":{"mode":"production"}}]},
                   "FlinkApplicationConfigurationUpdate":{
                     "ParallelismConfigurationUpdate":{"ConfigurationTypeUpdate":"CUSTOM","ParallelismUpdate":3},
                     "MonitoringConfigurationUpdate":{"ConfigurationTypeUpdate":"CUSTOM","LogLevelUpdate":"INFO","MetricsLevelUpdate":"APPLICATION"}}}}
                """);
        ObjectNode maintenance = call(first, "UpdateApplicationMaintenanceConfiguration", """
                {"ApplicationName":"metadata","ApplicationMaintenanceConfigurationUpdate":{
                  "ApplicationMaintenanceWindowStartTimeUpdate":"22:30"}}
                """);
        assertEquals("06:30", maintenance.path("ApplicationMaintenanceConfigurationDescription")
                .path("ApplicationMaintenanceWindowEndTime").asText());
        KinesisAnalyticsV2Service reloaded = service(ACCOUNT, REGION);
        ObjectNode detail = (ObjectNode) call(reloaded, "DescribeApplication", """
                {"ApplicationName":"metadata"}
                """).get("ApplicationDetail");
        assertEquals(2, detail.path("ApplicationVersionId").asInt());
        assertEquals("READY", detail.path("ApplicationStatus").asText());
        assertEquals("22:30", detail.path("ApplicationMaintenanceConfigurationDescription")
                .path("ApplicationMaintenanceWindowStartTime").asText());
        JsonNode config = detail.path("ApplicationConfigurationDescription");
        assertEquals("production", config.path("EnvironmentPropertyDescriptions")
                .path("PropertyGroupDescriptions").get(0).path("PropertyMap").path("mode").asText());
        JsonNode parallel = config.path("FlinkApplicationConfigurationDescription").path("ParallelismConfigurationDescription");
        assertEquals(3, parallel.path("Parallelism").asInt());
        assertEquals(1, parallel.path("ParallelismPerKPU").asInt());
        assertFalse(parallel.path("AutoScalingEnabled").asBoolean());
        assertFalse(config.has("ApplicationCodeConfigurationDescription"));
        assertFalse(parallel.has("CurrentParallelism"));
        ObjectNode versionResponse = call(reloaded, "DescribeApplicationVersion", """
                {"ApplicationName":"metadata","ApplicationVersionId":1}
                """);
        assertEquals(1, versionResponse.path("ApplicationVersionDetail").path("ApplicationVersionId").asInt());
        assertFalse(versionResponse.has("ApplicationDetail"));
        FlinkApplication initial = reloaded.describeApplicationVersion("metadata", 1L);
        assertEquals("test", initial.getEnvironmentProperties().get("app").get("mode"));
        assertEquals(2, initial.getParallelism());
        initial.getEnvironmentProperties().get("app").put("mode", "tamper");
        assertEquals("test", reloaded.describeApplicationVersion("metadata", 1L).getEnvironmentProperties().get("app").get("mode"));
        ObjectNode operation = reloaded.listApplicationOperations("metadata", "UpdateApplication", "SUCCESSFUL").getFirst();
        assertTrue(operation.path("StartTime").isNumber());
        assertTrue(operation.path("EndTime").isNumber());
        assertEquals(1, operation.path("ApplicationVersionChangeDetails").path("ApplicationVersionUpdatedFrom").asInt());
        assertEquals(2, operation.path("ApplicationVersionChangeDetails").path("ApplicationVersionUpdatedTo").asInt());
        assertEquals(operation, reloaded.describeApplicationOperation("metadata", operation.path("OperationId").asText()));
        verifyNoInteractions(containers);
    }

    @Test
    void loggingLifecycleUsesCasAndPersistsRemovalAndDeletion() throws Exception {
        KinesisAnalyticsV2Service first = service(ACCOUNT, REGION);
        ObjectNode created = create(first);
        ObjectNode added = call(first, "AddApplicationCloudWatchLoggingOption", """
                {"ApplicationName":"metadata","CurrentApplicationVersionId":1,
                 "CloudWatchLoggingOption":{"LogStreamARN":"%s"}}
                """.formatted(STREAM));
        assertEquals(2, added.path("ApplicationVersionId").asInt());
        String id = added.path("CloudWatchLoggingOptionDescriptions").get(0).path("CloudWatchLoggingOptionId").asText();
        assertFalse(id.isBlank());
        assertEquals("ConcurrentModificationException", assertThrows(AwsException.class,
                () -> first.deleteLoggingOption("metadata", 1L, null, id)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> first.deleteLoggingOption("metadata", 2L, null, "missing")).getErrorCode());
        assertEquals(2, first.describeApplication("metadata").getApplicationVersionId());
        KinesisAnalyticsV2Service reloaded = service(ACCOUNT, REGION);
        assertEquals(STREAM, reloaded.describeApplication("metadata").getCloudWatchLoggingOptions().get(id));
        String token = reloaded.describeApplication("metadata").getConditionalToken();
        reloaded.deleteLoggingOption("metadata", null, token, id);
        KinesisAnalyticsV2Service removed = service(ACCOUNT, REGION);
        assertTrue(removed.describeApplication("metadata").getCloudWatchLoggingOptions().isEmpty());
        assertEquals(STREAM, removed.describeApplicationVersion("metadata", 2L).getCloudWatchLoggingOptions().get(id));
        removed.deleteApplication("metadata", removed.describeApplication("metadata").getCreateTimestamp());
        KinesisAnalyticsV2Service deleted = service(ACCOUNT, REGION);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> deleted.describeApplicationVersion("metadata", 1L)).getErrorCode());
        assertEquals(0, deleted.listApplications().size());
        create(deleted);
        assertEquals(1, deleted.listApplicationVersions("metadata").size());
        assertTrue(deleted.listApplicationOperations("metadata", null, null).isEmpty());
        assertEquals(created.path("ApplicationARN").asText(), deleted.describeApplication("metadata").getApplicationArn());
    }

    @Test
    void createTimeLoggingOptionsUpdateWithoutChangingTheirIdentity() throws Exception {
        KinesisAnalyticsV2Service service = service(ACCOUNT, REGION);
        ObjectNode created = call(service, "CreateApplication", """
                {"ApplicationName":"metadata","RuntimeEnvironment":"FLINK-1_20","ServiceExecutionRole":"role",
                 "CloudWatchLoggingOptions":[{"LogStreamARN":"%s"}]}
                """.formatted(STREAM));
        String id = created.path("ApplicationDetail").path("CloudWatchLoggingOptionDescriptions").get(0)
                .path("CloudWatchLoggingOptionId").asText();
        assertEquals("InvalidArgumentException", assertThrows(AwsException.class,
                () -> service.addLoggingOption("metadata", 1L, null, STREAM)).getErrorCode());
        ObjectNode updated = call(service, "UpdateApplication", """
                {"ApplicationName":"metadata","CurrentApplicationVersionId":1,
                 "CloudWatchLoggingOptionUpdates":[{"CloudWatchLoggingOptionId":"%s","LogStreamARNUpdate":"%s"}]}
                """.formatted(id, STREAM + "-updated"));
        assertEquals(id, updated.path("ApplicationDetail").path("CloudWatchLoggingOptionDescriptions").get(0)
                .path("CloudWatchLoggingOptionId").asText());
        assertEquals(STREAM + "-updated", service.describeApplication("metadata").getCloudWatchLoggingOptions().get(id));
        assertEquals(STREAM, service.describeApplicationVersion("metadata", 1L).getCloudWatchLoggingOptions().get(id));
        assertFalse(updated.path("OperationId").asText().isBlank());
        verifyNoInteractions(containers);
    }

    @Test
    void invalidUpdatesAreAtomicAndDoNotProduceHistory() throws Exception {
        KinesisAnalyticsV2Service service = service(ACCOUNT, REGION);
        create(service);
        for (String time : List.of("24:00", "9:00", "12:60", "", "12:00:00")) {
            assertEquals("InvalidArgumentException", assertThrows(AwsException.class,
                    () -> service.updateMaintenance("metadata", time)).getErrorCode());
        }
        assertEquals("InvalidArgumentException", assertThrows(AwsException.class,
                () -> service.addLoggingOption("metadata", 1L, null, STREAM.replace(ACCOUNT, "111111111111"))).getErrorCode());
        assertEquals("InvalidArgumentException", assertThrows(AwsException.class,
                () -> call(service, "UpdateApplication", """
                    {"ApplicationName":"metadata","CurrentApplicationVersionId":1,
                     "ServiceExecutionRoleUpdate":"changed",
                     "ApplicationConfigurationUpdate":{"FlinkApplicationConfigurationUpdate":{
                       "ParallelismConfigurationUpdate":{"ParallelismUpdate":0}}}}
                    """)).getErrorCode());
        assertEquals(1, service.describeApplication("metadata").getApplicationVersionId());
        assertEquals("arn:aws:iam::000000000000:role/flink", service.describeApplication("metadata").getServiceExecutionRole());
        assertTrue(service.listApplicationOperations("metadata", null, null).isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describeApplicationOperation("metadata", "missing")).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describeApplicationVersion("metadata", 999L)).getErrorCode());
        service.describeApplication("metadata").setApplicationStatus(ApplicationStatus.STARTING);
        assertEquals("ResourceInUseException", assertThrows(AwsException.class,
                () -> service.updateMaintenance("metadata", "02:00")).getErrorCode());
        verifyNoInteractions(containers);
    }

    @Test
    void versionsAndOperationsPaginateAndRejectForeignTokens() throws Exception {
        KinesisAnalyticsV2Service service = service(ACCOUNT, REGION);
        create(service);
        service.updateApplication("metadata", 1L, "role-two");
        service.updateApplication("metadata", 2L, "role-three");
        ObjectNode first = call(service, "ListApplicationVersions", """
                {"ApplicationName":"metadata","Limit":1}
                """);
        assertEquals(3, first.path("ApplicationVersionSummaries").get(0).path("ApplicationVersionId").asInt());
        ObjectNode nextRequest = mapper.createObjectNode().put("ApplicationName", "metadata").put("Limit", 1)
                .put("NextToken", first.path("NextToken").asText());
        assertEquals(2, call(service, "ListApplicationVersions", nextRequest.toString())
                .path("ApplicationVersionSummaries").get(0).path("ApplicationVersionId").asInt());
        assertEquals("InvalidArgumentException", assertThrows(AwsException.class,
                () -> call(service, "ListApplicationOperations", nextRequest.toString())).getErrorCode());
        ObjectNode operations = call(service, "ListApplicationOperations", """
                {"ApplicationName":"metadata","Limit":1,"Operation":"UpdateApplication","OperationStatus":"SUCCESSFUL"}
                """);
        assertEquals(1, operations.path("ApplicationOperationInfoList").size());
        assertTrue(operations.has("NextToken"));
        assertFalse(operations.path("ApplicationOperationInfoList").get(0).has("ApplicationVersionChangeDetails"));
        for (String invalid : List.of("0", "51", "1.5", "\"1\"")) {
            assertEquals("InvalidArgumentException", assertThrows(AwsException.class,
                    () -> call(service, "ListApplicationVersions", "{\"ApplicationName\":\"metadata\",\"Limit\":" + invalid + "}")).getErrorCode());
        }
    }

    @Test
    void accountAndRegionIdentityAlsoScopesTagsVersionsAndOperations() throws Exception {
        KinesisAnalyticsV2Service east = service(ACCOUNT, REGION);
        create(east);
        String eastArn = east.describeApplication("metadata").getApplicationArn();
        east.updateApplication("metadata", 1L, "east-role");
        String operation = east.listApplicationOperations("metadata", null, null).getFirst().path("OperationId").asText();
        for (String[] scope : List.of(new String[]{ACCOUNT, "us-west-2"}, new String[]{"111111111111", REGION})) {
            KinesisAnalyticsV2Service foreign = service(scope[0], scope[1]);
            assertTrue(foreign.listApplications().isEmpty());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> foreign.describeApplication("metadata")).getErrorCode());
            foreign.createApplication("metadata", "FLINK-1_20", "role", null, "STREAMING");
            assertEquals(1, foreign.listApplicationVersions("metadata").size());
            assertTrue(foreign.listApplicationOperations("metadata", null, null).isEmpty());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> foreign.describeApplicationOperation("metadata", operation)).getErrorCode());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> foreign.tagResource(eastArn, Map.of("tamper", "true"))).getErrorCode());
        }
        assertEquals(2, service(ACCOUNT, REGION).describeApplication("metadata").getApplicationVersionId());
    }

    @Test
    void legacyStateMigratesOnlyToItsOwnerAndDoesNotInventEarlierVersions() {
        PersistentStorage<String, FlinkApplication> legacyStore = new PersistentStorage<>(directory.resolve("apps.json"),
                new TypeReference<Map<String, FlinkApplication>>() {});
        FlinkApplication legacy = new FlinkApplication("metadata",
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/metadata",
                "FLINK-1_20", "role", "STREAMING");
        legacy.setAccountId(ACCOUNT);
        legacy.setApplicationVersionId(9L);
        legacyStore.put("metadata", legacy);
        KinesisAnalyticsV2Service foreign = service("111111111111", REGION);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> foreign.describeApplication("metadata")).getErrorCode());
        KinesisAnalyticsV2Service owner = service(ACCOUNT, REGION);
        assertEquals(List.of(9L), owner.listApplicationVersions("metadata").stream()
                .map(FlinkApplication::getApplicationVersionId).toList());
        owner.updateApplication("metadata", 9L, "updated-role");
        KinesisAnalyticsV2Service reloaded = service(ACCOUNT, REGION);
        assertEquals(List.of(10L, 9L), reloaded.listApplicationVersions("metadata").stream()
                .map(FlinkApplication::getApplicationVersionId).toList());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> reloaded.describeApplicationVersion("metadata", 1L)).getErrorCode());
        assertEquals(1, reloaded.listApplications().size());
    }

    @Test
    void missingRestoreFailsBeforeContainerStartupAndDoesNotInventSnapshots() throws Exception {
        KinesisAnalyticsV2Service service = service(ACCOUNT, REGION);
        create(service);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> call(service, "StartApplication", """
                    {"ApplicationName":"metadata","RunConfiguration":{"ApplicationRestoreConfiguration":{
                      "ApplicationRestoreType":"RESTORE_FROM_CUSTOM_SNAPSHOT","SnapshotName":"missing"}}}
                    """)).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.createApplicationSnapshot("metadata", "no-job")).getErrorCode());
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.rollbackApplication("metadata", 1L)).getErrorCode());
        assertTrue(service.listApplicationSnapshots("metadata").isEmpty());
        assertTrue(service.listApplicationOperations("metadata", null, null).isEmpty());
        assertEquals(ApplicationStatus.READY, service.describeApplication("metadata").getApplicationStatus());
        verifyNoInteractions(containers);
    }
}
