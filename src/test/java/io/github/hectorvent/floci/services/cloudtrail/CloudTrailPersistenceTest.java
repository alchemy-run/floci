package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudTrailPersistenceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regions = new RegionResolver("us-east-1", "000000000000");

    @Test
    void historyLakeMetadataAndCollectedEventsSurviveReconstruction(@TempDir Path directory) {
        StorageFactory first = factory(directory);
        CloudTrailLakeService lake = new CloudTrailLakeService(first, regions, mapper);
        CloudTrailEventService events = events(first, lake);
        ObjectNode created = lake.handle("CreateEventDataStore", mapper.createObjectNode()
                .put("Name", "persistent-lake").put("RetentionPeriod", 7)
                .put("TerminationProtectionEnabled", false).put("MultiRegionEnabled", false), "us-east-1");
        String arn = created.path("EventDataStoreArn").asText();
        lake.updateTags(arn, "us-east-1", Map.of("team", "audit"), java.util.List.of());
        events.record("ssm", "PutParameter", "us-east-1", "test", false,
                mapper.createObjectNode().put("name", "/persisted"), "127.0.0.1", "test-client", "request-one", null, null);
        ObjectNode request = mapper.createObjectNode().put("EventDataStore", arn);
        lake.handle("StopEventDataStoreIngestion", request, "us-east-1");
        first.shutdownAll();

        StorageFactory second = factory(directory);
        try {
            CloudTrailLakeService restoredLake = new CloudTrailLakeService(second, regions, mapper);
            CloudTrailEventService restoredEvents = events(second, restoredLake);
            ObjectNode observed = restoredLake.handle("GetEventDataStore", request, "us-east-1");
            assertEquals("persistent-lake", observed.path("Name").asText());
            assertEquals("STOPPED_INGESTION", observed.path("Status").asText());
            assertEquals(7, observed.path("RetentionPeriod").asInt());
            assertEquals(Map.of("team", "audit"), restoredLake.tags(arn, "us-east-1"));
            var records = restoredLake.collectedEvents(arn, "us-east-1");
            assertEquals(1, records.size());
            assertEquals("request-one", records.getFirst().path("requestID").asText());
            var history = restoredEvents.lookup(mapper.createObjectNode(), "us-east-1").path("Events");
            assertEquals(1, history.size());
            assertEquals(records.getFirst().path("eventID").asText(), history.get(0).path("EventId").asText());
            assertTrue(restoredEvents.lookup(mapper.createObjectNode(), "us-west-2").path("Events").isEmpty());
            restoredEvents.record("ssm", "PutParameter", "us-east-1", "test", false,
                    mapper.createObjectNode().put("name", "/not-ingested"), null, null, "request-two", null, null);
            assertEquals(1, restoredLake.collectedEvents(arn, "us-east-1").size());
            assertEquals(2, restoredEvents.lookup(mapper.createObjectNode(), "us-east-1").path("Events").size());
            restoredLake.handle("DeleteEventDataStore", request, "us-east-1");
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = factory(directory);
        try {
            CloudTrailLakeService deletedLake = new CloudTrailLakeService(third, regions, mapper);
            assertEquals("PENDING_DELETION", deletedLake.handle("GetEventDataStore", request, "us-east-1")
                    .path("Status").asText());
            deletedLake.handle("RestoreEventDataStore", request, "us-east-1");
            assertEquals(1, deletedLake.collectedEvents(arn, "us-east-1").size());
        } finally {
            third.shutdownAll();
        }
    }

    @Test
    void historyExpiresAfterNinetyDays(@TempDir Path directory) {
        StorageFactory factory = factory(directory);
        try {
            var history = factory.create("cloudtrail", "cloudtrail-event-history.json",
                    new TypeReference<Map<String, ObjectNode>>() {});
            history.put("us-east-1:expired", mapper.createObjectNode().put("eventID", "expired")
                    .put("eventTime", Instant.now().minusSeconds(91L * 86400).toString()));
            CloudTrailLakeService lake = new CloudTrailLakeService(factory, regions, mapper);
            assertTrue(events(factory, lake).lookup(mapper.createObjectNode(), "us-east-1").path("Events").isEmpty());
            assertTrue(history.keys().isEmpty());
        } finally {
            factory.shutdownAll();
        }
    }

    private CloudTrailEventService events(StorageFactory factory, CloudTrailLakeService lake) {
        IamService iam = mock(IamService.class);
        CloudTrailService trails = new CloudTrailService(factory, regions, iam, mapper);
        return new CloudTrailEventService(factory, regions, mapper, trails, lake, mock(EventBridgeService.class), iam);
    }

    private StorageFactory factory(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("cloudtrail")).thenReturn("persistent");
        when(access.storageFlushInterval("cloudtrail")).thenReturn(1000L);
        return new StorageFactory(config, access);
    }
}
