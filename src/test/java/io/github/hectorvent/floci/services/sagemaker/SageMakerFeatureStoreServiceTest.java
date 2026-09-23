package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sagemaker.SageMakerStateSupport.Scheduler;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.FeatureGroupResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.FeatureRecordResource;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SageMakerFeatureStoreServiceTest {
    private static final String REGION = "us-east-1";
    private static final String GROUP = """
            {"FeatureGroupName":"Users","RecordIdentifierFeatureName":"user_id","EventTimeFeatureName":"event_time",
             "FeatureDefinitions":[{"FeatureName":"user_id","FeatureType":"String"},
                                   {"FeatureName":"event_time","FeatureType":"String"},
                                   {"FeatureName":"clicks","FeatureType":"Integral"}],
             "OnlineStoreConfig":{"EnableOnlineStore":true},
             "Tags":[{"Key":"purpose","Value":"test"}]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final InMemoryStorage<String, FeatureGroupResource> groups = new InMemoryStorage<>();
    private final InMemoryStorage<String, FeatureRecordResource> records = new InMemoryStorage<>();
    private final List<String> events = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private Set<String> buckets = Set.of();
    private Set<String> roles = Set.of();

    @Test
    void describeMissingFeatureGroupIsResourceNotFound() {
        AwsException missing = assertThrows(AwsException.class, () -> service().describeFeatureGroup(
                json("{\"FeatureGroupName\":\"alchemy-nonexistent-feature-group-probe\"}"), REGION));
        assertEquals("ResourceNotFound", missing.getErrorCode());
        assertTrue(missing.getMessage().contains("Resource Not Found"));
    }

    @Test
    void featureGroupTransitionsFromCreatingToCreatedAndThroughDeletingToGone() {
        SageMakerFeatureStoreService service = service();
        String arn = service.createFeatureGroup(json(GROUP), REGION).path("FeatureGroupArn").asText();
        assertEquals("arn:aws:sagemaker:us-east-1:000000000000:feature-group/users", arn);
        assertEquals("Creating", describe(service, "Users").path("FeatureGroupStatus").asText());

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createFeatureGroup(json(GROUP), REGION));
        assertEquals("ResourceInUse", duplicate.getErrorCode());
        AwsException deleteWhileCreating = assertThrows(AwsException.class,
                () -> service.deleteFeatureGroup(json("{\"FeatureGroupName\":\"Users\"}"), REGION));
        assertEquals("ResourceInUse", deleteWhileCreating.getErrorCode());

        clock.advance(SageMakerFeatureStoreService.CREATE_DURATION);
        JsonNode created = describe(service, arn);
        assertEquals("Created", created.path("FeatureGroupStatus").asText());
        assertTrue(created.path("OnlineStoreConfig").path("EnableOnlineStore").asBoolean());
        assertEquals(3, created.path("FeatureDefinitions").size());
        assertEquals("OnDemand", created.path("ThroughputConfig").path("ThroughputMode").asText());
        assertEquals(List.of("Created"), events);

        service.deleteFeatureGroup(json("{\"FeatureGroupName\":\"Users\"}"), REGION);
        assertEquals("Deleting", describe(service, "Users").path("FeatureGroupStatus").asText());
        clock.advance(SageMakerFeatureStoreService.DELETE_DURATION);
        AwsException gone = assertThrows(AwsException.class, () -> describe(service, "Users"));
        assertEquals("ResourceNotFound", gone.getErrorCode());
        assertEquals(List.of("Created", "Deleting"), events);
    }

    @Test
    void scheduledTransitionCompletesWithoutAnObserver() {
        SageMakerFeatureStoreService service = service();
        service.createFeatureGroup(json(GROUP), REGION);
        clock.advance(SageMakerFeatureStoreService.CREATE_DURATION);
        scheduled.forEach(Runnable::run);
        assertEquals(List.of("Created"), events);
        assertEquals("Created", groups.scan(k -> true).get(0).featureGroupStatus);
    }

    @Test
    void createValidatesDefinitionsStoresRoleAndBucket() {
        SageMakerFeatureStoreService service = service();
        assertValidation(service, GROUP.replace("\"RecordIdentifierFeatureName\":\"user_id\"",
                "\"RecordIdentifierFeatureName\":\"missing\""));
        assertValidation(service, GROUP.replace("\"FeatureType\":\"Integral\"", "\"FeatureType\":\"Boolean\""));
        assertValidation(service, GROUP.replace("\"FeatureName\":\"clicks\"", "\"FeatureName\":\"is_deleted\""));
        assertValidation(service, GROUP.replace("\"FeatureName\":\"clicks\"", "\"FeatureName\":\"USER_ID\""));
        assertValidation(service, GROUP.replace("{\"EnableOnlineStore\":true}", "{\"EnableOnlineStore\":false}"));

        String offline = GROUP.replace("\"OnlineStoreConfig\":{\"EnableOnlineStore\":true}",
                "\"OfflineStoreConfig\":{\"S3StorageConfig\":{\"S3Uri\":\"s3://offline-bucket/prefix\"}},"
                        + "\"RoleArn\":\"arn:aws:iam::000000000000:role/fs\"");
        AwsException missingRole = assertValidation(service, offline);
        assertTrue(missingRole.getMessage().contains("RoleArn"));
        roles = Set.of("arn:aws:iam::000000000000:role/fs");
        AwsException missingBucket = assertValidation(service(), offline);
        assertTrue(missingBucket.getMessage().contains("offline-bucket"));

        buckets = Set.of("offline-bucket");
        SageMakerFeatureStoreService withDeps = service();
        withDeps.createFeatureGroup(json(offline), REGION);
        clock.advance(SageMakerFeatureStoreService.CREATE_DURATION);
        JsonNode described = describe(withDeps, "Users");
        assertEquals("Blocked", described.path("OfflineStoreStatus").path("Status").asText());
        assertEquals(SageMakerFeatureStoreService.OFFLINE_STORE_BLOCKED_REASON,
                described.path("OfflineStoreStatus").path("BlockedReason").asText());
        assertTrue(described.path("OfflineStoreConfig").path("S3StorageConfig").path("ResolvedOutputS3Uri").asText()
                .startsWith("s3://offline-bucket/prefix/000000000000/sagemaker/us-east-1/offline-store/Users-"));
    }

    @Test
    void onlineStoreRecordLifecycle() {
        SageMakerFeatureStoreService service = createdGroup();
        service.putRecord("Users", record("u1", "2026-01-01T00:00:01.000Z", "7"), REGION);
        assertEquals(Map.of("user_id", "u1", "event_time", "2026-01-01T00:00:01.000Z", "clicks", "7"),
                values(service.getRecord("Users", "u1", null, null, REGION)));

        // An older EventTime is historic and leaves the online record untouched.
        service.putRecord("Users", record("u1", "2026-01-01T00:00:00Z", "1"), REGION);
        assertEquals("7", values(service.getRecord("Users", "u1", null, null, REGION)).get("clicks"));

        assertFalse(service.getRecord("Users", "unknown", null, null, REGION).has("Record"));
        assertEquals(Map.of("clicks", "7"),
                values(service.getRecord("Users", "u1", List.of("clicks"), null, REGION)));

        // SoftDelete hides the record from reads, but only with a later EventTime.
        service.deleteRecord("Users", "u1", "2026-01-01T00:00:00Z", null, null, REGION);
        assertTrue(service.getRecord("Users", "u1", null, null, REGION).has("Record"));
        AwsException staleHardDelete = assertThrows(AwsException.class, () ->
                service.deleteRecord("Users", "u1", "2026-01-01T00:00:00Z", null, "HardDelete", REGION));
        assertEquals("ValidationError", staleHardDelete.getErrorCode());
        service.deleteRecord("Users", "u1", "2026-01-01T00:00:02Z", null, null, REGION);
        assertFalse(service.getRecord("Users", "u1", null, null, REGION).has("Record"));

        JsonNode visible = service.listRecords("Users", json("{}"), REGION);
        assertEquals(0, visible.path("RecordIdentifiers").size());
        JsonNode withDeleted = service.listRecords("Users", json("{\"IncludeSoftDeletedRecords\":true}"), REGION);
        assertEquals("u1", withDeleted.path("RecordIdentifiers").get(0).asText());

        // A newer write restores a soft-deleted record.
        service.putRecord("Users", record("u1", "2026-01-01T00:00:03Z", "9"), REGION);
        assertEquals("9", values(service.getRecord("Users", "u1", null, null, REGION)).get("clicks"));
    }

    @Test
    void putRecordValidatesAgainstFeatureDefinitions() {
        SageMakerFeatureStoreService service = createdGroup();
        assertRuntimeValidation(() -> service.putRecord("Users", record("u1", "2026-01-01T00:00:00Z", "seven"), REGION));
        assertRuntimeValidation(() -> service.putRecord("Users", record("u1", "yesterday", "7"), REGION));
        assertRuntimeValidation(() -> service.putRecord("Users", json("""
                {"Record":[{"FeatureName":"user_id","ValueAsString":"u1"},{"FeatureName":"clicks","ValueAsString":"1"}]}
                """), REGION));
        assertRuntimeValidation(() -> service.putRecord("Users", json("""
                {"Record":[{"FeatureName":"user_id","ValueAsString":"u1"},
                           {"FeatureName":"event_time","ValueAsString":"2026-01-01T00:00:00Z"},
                           {"FeatureName":"unknown","ValueAsString":"x"}]}
                """), REGION));
        AwsException missingGroup = assertThrows(AwsException.class,
                () -> service.putRecord("Nope", record("u1", "2026-01-01T00:00:00Z", "1"), REGION));
        assertEquals("ValidationError", missingGroup.getErrorCode());
        assertTrue(missingGroup.getMessage().contains("Resource Not Found"));
    }

    @Test
    void batchWriteAndBatchGetScopeRecordsPerFeatureGroup() {
        SageMakerFeatureStoreService service = createdGroup();
        ObjectNode write = service.batchWriteRecord(json("""
                {"Entries":[
                  {"FeatureGroupName":"Users","Record":[{"FeatureName":"user_id","ValueAsString":"b1"},
                     {"FeatureName":"event_time","ValueAsString":"2026-01-01T00:00:00Z"},{"FeatureName":"clicks","ValueAsString":"11"}]},
                  {"FeatureGroupName":"Users","Record":[{"FeatureName":"user_id","ValueAsString":"b2"},
                     {"FeatureName":"event_time","ValueAsString":"2026-01-01T00:00:00Z"},{"FeatureName":"clicks","ValueAsString":"x"}]},
                  {"FeatureGroupName":"Other","Record":[{"FeatureName":"user_id","ValueAsString":"b3"}]}]}
                """), REGION);
        assertEquals(2, write.path("Errors").size());
        assertEquals("ValidationError", write.path("Errors").get(0).path("ErrorCode").asText());
        assertEquals("b2", write.path("Errors").get(0).path("Entry").path("Record").get(0).path("ValueAsString").asText());

        ObjectNode read = service.batchGetRecord(json("""
                {"Identifiers":[{"FeatureGroupName":"Users","RecordIdentifiersValueAsString":["b1","b2","missing"]},
                                {"FeatureGroupName":"Other","RecordIdentifiersValueAsString":["b3"]}]}
                """), REGION);
        assertEquals(1, read.path("Records").size());
        assertEquals("b1", read.path("Records").get(0).path("RecordIdentifierValueAsString").asText());
        assertEquals(1, read.path("Errors").size());
        assertEquals("b3", read.path("Errors").get(0).path("RecordIdentifierValueAsString").asText());
    }

    @Test
    void recordsExpireAfterTheirTtlAndDisappearWithTheirGroup() {
        SageMakerFeatureStoreService service = createdGroup();
        String now = clock.instant().toString();
        service.putRecord("Users", json("""
                {"Record":[{"FeatureName":"user_id","ValueAsString":"t1"},
                           {"FeatureName":"event_time","ValueAsString":"%s"},
                           {"FeatureName":"clicks","ValueAsString":"1"}],
                 "TtlDuration":{"Unit":"Minutes","Value":1}}
                """.formatted(now)), REGION);
        JsonNode live = service.getRecord("Users", "t1", null, "Enabled", REGION);
        assertTrue(live.has("ExpiresAt"));
        clock.advance(Duration.ofMinutes(2));
        assertFalse(service.getRecord("Users", "t1", null, null, REGION).has("Record"));

        service.putRecord("Users", record("kept", "2026-01-01T00:00:00Z", "1"), REGION);
        service.deleteFeatureGroup(json("{\"FeatureGroupName\":\"Users\"}"), REGION);
        clock.advance(SageMakerFeatureStoreService.DELETE_DURATION);
        service.listFeatureGroups(json("{}"), REGION);
        assertTrue(records.keys().isEmpty());
    }

    @Test
    void listFeatureGroupsFiltersAndPaginates() {
        SageMakerFeatureStoreService service = service();
        for (String name : List.of("alpha", "beta", "gamma")) {
            service.createFeatureGroup(json(GROUP.replace("\"Users\"", "\"" + name + "\"")), REGION);
        }
        clock.advance(SageMakerFeatureStoreService.CREATE_DURATION);
        JsonNode first = service.listFeatureGroups(json("{\"MaxResults\":2,\"SortBy\":\"Name\",\"SortOrder\":\"Ascending\"}"), REGION);
        assertEquals(2, first.path("FeatureGroupSummaries").size());
        assertEquals("alpha", first.path("FeatureGroupSummaries").get(0).path("FeatureGroupName").asText());
        JsonNode second = service.listFeatureGroups(json("{\"MaxResults\":2,\"SortBy\":\"Name\",\"SortOrder\":\"Ascending\",\"NextToken\":\""
                + first.path("NextToken").asText() + "\"}"), REGION);
        assertEquals("gamma", second.path("FeatureGroupSummaries").get(0).path("FeatureGroupName").asText());
        assertFalse(second.has("NextToken"));
        assertEquals(1, service.listFeatureGroups(json("{\"NameContains\":\"et\"}"), REGION)
                .path("FeatureGroupSummaries").size());
    }

    @Test
    void tagsRouteByFeatureGroupArn() {
        SageMakerFeatureStoreService service = createdGroup();
        String arn = describe(service, "Users").path("FeatureGroupArn").asText();
        service.tags("AddTags", json("{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"owner\",\"Value\":\"me\"}]}"));
        service.tags("DeleteTags", json("{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"purpose\"]}"));
        JsonNode tags = service.tags("ListTags", json("{\"ResourceArn\":\"" + arn + "\"}")).orElseThrow().path("Tags");
        assertEquals(1, tags.size());
        assertEquals("owner", tags.get(0).path("Key").asText());
        assertTrue(service.tags("ListTags", json("{\"ResourceArn\":\"arn:aws:sagemaker:us-east-1:000000000000:model/m\"}"))
                .isEmpty());
    }

    private SageMakerFeatureStoreService createdGroup() {
        SageMakerFeatureStoreService service = service();
        service.createFeatureGroup(json(GROUP), REGION);
        clock.advance(SageMakerFeatureStoreService.CREATE_DURATION);
        return service;
    }

    private SageMakerFeatureStoreService service() {
        Set<String> knownBuckets = buckets;
        Set<String> knownRoles = roles;
        Scheduler scheduler = (delay, task) -> scheduled.add(task);
        return new SageMakerFeatureStoreService(groups, records, new RegionResolver(REGION, "000000000000"), mapper,
                clock, knownBuckets::contains, knownRoles::contains,
                (region, account, detailType, arn, detail) -> events.add(detail.path("FeatureGroupStatus").asText()),
                scheduler);
    }

    private JsonNode describe(SageMakerFeatureStoreService service, String nameOrArn) {
        return service.describeFeatureGroup(json("{\"FeatureGroupName\":\"" + nameOrArn + "\"}"), REGION);
    }

    private AwsException assertValidation(SageMakerFeatureStoreService service, String request) {
        AwsException e = assertThrows(AwsException.class, () -> service.createFeatureGroup(json(request), REGION));
        assertEquals("ValidationException", e.getErrorCode());
        return e;
    }

    private static void assertRuntimeValidation(Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals("ValidationError", e.getErrorCode());
    }

    private JsonNode record(String userId, String eventTime, String clicks) {
        return json("""
                {"Record":[{"FeatureName":"user_id","ValueAsString":"%s"},
                           {"FeatureName":"event_time","ValueAsString":"%s"},
                           {"FeatureName":"clicks","ValueAsString":"%s"}]}
                """.formatted(userId, eventTime, clicks));
    }

    private static Map<String, String> values(JsonNode response) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        response.path("Record").forEach(v -> out.put(v.path("FeatureName").asText(), v.path("ValueAsString").asText()));
        return out;
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
