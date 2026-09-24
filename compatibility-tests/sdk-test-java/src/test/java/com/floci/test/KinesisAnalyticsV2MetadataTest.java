package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2Client;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.AddApplicationCloudWatchLoggingOptionResponse;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ApplicationDetail;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ApplicationOperationInfo;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ConcurrentModificationException;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.InvalidArgumentException;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.InvalidRequestException;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ListApplicationVersionsResponse;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ResourceNotFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KinesisAnalyticsV2MetadataTest {
    private static final String ROLE = "arn:aws:iam::000000000000:role/flink-metadata";

    @Test
    @DisplayName("READY application metadata, maintenance, logging and version history round trip through the SDK")
    void readyApplicationMetadataLifecycle() {
        String name = TestFixtures.uniqueName("flink-metadata");
        try (KinesisAnalyticsV2Client client = TestFixtures.kinesisAnalyticsV2Client()) {
            ApplicationDetail created = client.createApplication(request -> request.applicationName(name)
                    .runtimeEnvironment("FLINK-1_20").serviceExecutionRole(ROLE)
                    .applicationConfiguration(configuration -> configuration
                            .environmentProperties(properties -> properties.propertyGroups(group -> group
                                    .propertyGroupId("app").propertyMap(Map.of("mode", "test")))))).applicationDetail();
            try {
                assertThat(created.applicationVersionId()).isEqualTo(1L);
                assertThat(client.listApplicationOperations(request -> request.applicationName(name))
                        .applicationOperationInfoList()).isEmpty();
                ApplicationDetail updated = client.updateApplication(request -> request.applicationName(name)
                        .currentApplicationVersionId(1L)
                        .applicationConfigurationUpdate(configuration -> configuration
                                .environmentPropertyUpdates(properties -> properties.propertyGroups(group -> group
                                        .propertyGroupId("app").propertyMap(Map.of("mode", "production")))))).applicationDetail();
                assertThat(updated.applicationVersionId()).isEqualTo(2L);
                assertThat(updated.applicationConfigurationDescription().environmentPropertyDescriptions()
                        .propertyGroupDescriptions().get(0).propertyMap()).containsEntry("mode", "production");
                client.updateApplicationMaintenanceConfiguration(request -> request.applicationName(name)
                        .applicationMaintenanceConfigurationUpdate(configuration -> configuration
                                .applicationMaintenanceWindowStartTimeUpdate("22:30")));
                ApplicationDetail maintained = client.describeApplication(request -> request.applicationName(name)).applicationDetail();
                assertThat(maintained.applicationMaintenanceConfigurationDescription().applicationMaintenanceWindowStartTime())
                        .isEqualTo("22:30");
                assertThat(maintained.applicationMaintenanceConfigurationDescription().applicationMaintenanceWindowEndTime())
                        .isEqualTo("06:30");
                assertThat(maintained.applicationVersionId()).isEqualTo(2L);
                String stream = created.applicationARN().replace(":kinesisanalytics:", ":logs:")
                        .replace("application/" + name, "log-group:" + name + ":log-stream:errors");
                AddApplicationCloudWatchLoggingOptionResponse added = client.addApplicationCloudWatchLoggingOption(request -> request
                        .applicationName(name).currentApplicationVersionId(2L)
                        .cloudWatchLoggingOption(option -> option.logStreamARN(stream)));
                assertThat(added.applicationVersionId()).isEqualTo(3L);
                assertThat(added.cloudWatchLoggingOptionDescriptions()).hasSize(1);
                String id = added.cloudWatchLoggingOptionDescriptions().get(0).cloudWatchLoggingOptionId();
                assertThat(id).isNotBlank();
                assertThat(client.describeApplication(request -> request.applicationName(name)).applicationDetail()
                        .cloudWatchLoggingOptionDescriptions().get(0).logStreamARN()).isEqualTo(stream);
                assertThatThrownBy(() -> client.deleteApplicationCloudWatchLoggingOption(request -> request
                        .applicationName(name).currentApplicationVersionId(2L).cloudWatchLoggingOptionId(id)))
                        .isInstanceOf(ConcurrentModificationException.class);
                client.deleteApplicationCloudWatchLoggingOption(request -> request.applicationName(name)
                        .currentApplicationVersionId(3L).cloudWatchLoggingOptionId(id));
                assertThat(client.describeApplication(request -> request.applicationName(name)).applicationDetail()
                        .cloudWatchLoggingOptionDescriptions()).isEmpty();
                assertThat(client.describeApplicationVersion(request -> request.applicationName(name).applicationVersionId(3L))
                        .applicationVersionDetail().cloudWatchLoggingOptionDescriptions().get(0).cloudWatchLoggingOptionId())
                        .isEqualTo(id);
                assertThat(client.describeApplicationVersion(request -> request.applicationName(name).applicationVersionId(1L))
                        .applicationVersionDetail().applicationConfigurationDescription().environmentPropertyDescriptions()
                        .propertyGroupDescriptions().get(0).propertyMap()).containsEntry("mode", "test");
                ListApplicationVersionsResponse versions = client.listApplicationVersions(request -> request.applicationName(name).limit(1));
                assertThat(versions.applicationVersionSummaries()).hasSize(1);
                assertThat(versions.applicationVersionSummaries().get(0).applicationVersionId()).isEqualTo(4L);
                assertThat(versions.nextToken()).isNotBlank();
                assertThat(client.listApplicationVersions(request -> request.applicationName(name).limit(1).nextToken(versions.nextToken()))
                        .applicationVersionSummaries().get(0).applicationVersionId()).isEqualTo(3L);
                ApplicationOperationInfo operation = client.listApplicationOperations(request -> request.applicationName(name)
                        .operation("AddApplicationCloudWatchLoggingOption").operationStatus("SUCCESSFUL"))
                        .applicationOperationInfoList().get(0);
                assertThat(operation.operationId()).isEqualTo(added.operationId());
                assertThat(operation.startTime()).isNotNull();
                assertThat(operation.endTime()).isNotNull();
                assertThat(client.describeApplicationOperation(request -> request.applicationName(name).operationId(operation.operationId()))
                        .applicationOperationInfoDetails().applicationVersionChangeDetails().applicationVersionUpdatedTo()).isEqualTo(3L);
            } finally {
                client.deleteApplication(request -> request.applicationName(name).createTimestamp(created.createTimestamp()));
            }
            assertThatThrownBy(() -> client.describeApplication(request -> request.applicationName(name)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("Metadata validation and missing execution resources use typed SDK errors")
    void metadataAndExecutionNegativeResponses() {
        String name = TestFixtures.uniqueName("flink-metadata-negative");
        try (KinesisAnalyticsV2Client client = TestFixtures.kinesisAnalyticsV2Client()) {
            ApplicationDetail created = client.createApplication(request -> request.applicationName(name)
                    .runtimeEnvironment("FLINK-1_20").serviceExecutionRole(ROLE)).applicationDetail();
            try {
                assertThatThrownBy(() -> client.updateApplicationMaintenanceConfiguration(request -> request.applicationName(name)
                        .applicationMaintenanceConfigurationUpdate(configuration -> configuration.applicationMaintenanceWindowStartTimeUpdate("25:00"))))
                        .isInstanceOf(InvalidArgumentException.class);
                assertThatThrownBy(() -> client.describeApplicationVersion(request -> request.applicationName(name).applicationVersionId(99L)))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> client.describeApplicationOperation(request -> request.applicationName(name).operationId("missing")))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> client.startApplication(request -> request.applicationName(name)
                        .runConfiguration(configuration -> configuration.applicationRestoreConfiguration(restore -> restore
                                .applicationRestoreType("RESTORE_FROM_CUSTOM_SNAPSHOT").snapshotName("missing")))))
                        .isInstanceOf(ResourceNotFoundException.class);
                assertThatThrownBy(() -> client.createApplicationSnapshot(request -> request.applicationName(name).snapshotName("no-job")))
                        .isInstanceOf(InvalidRequestException.class);
                assertThat(client.listApplicationSnapshots(request -> request.applicationName(name)).snapshotSummaries()).isEmpty();
                assertThat(client.listApplicationOperations(request -> request.applicationName(name)).applicationOperationInfoList()).isEmpty();
                assertThat(client.describeApplication(request -> request.applicationName(name)).applicationDetail().applicationStatusAsString())
                        .isEqualTo("READY");
            } finally {
                client.deleteApplication(request -> request.applicationName(name).createTimestamp(created.createTimestamp()));
            }
        }
    }
}
