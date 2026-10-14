package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.CreateTrailResponse;
import software.amazon.awssdk.services.cloudtrail.model.DescribeTrailsResponse;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusResponse;
import software.amazon.awssdk.services.cloudtrail.model.Trail;
import software.amazon.awssdk.services.cloudtrail.model.TrailNotFoundException;
import software.amazon.awssdk.services.cloudtrail.model.UpdateTrailResponse;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CloudTrail")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudTrailTest {

    private static CloudTrailClient cloudTrail;
    private static String trailName;
    private static String bucketName;
    private static String updatedBucketName;

    @BeforeAll
    static void setup() {
        cloudTrail = TestFixtures.cloudTrailClient();
        trailName = TestFixtures.uniqueName("ct-sdk");
        bucketName = TestFixtures.uniqueName("ct-bucket");
        updatedBucketName = TestFixtures.uniqueName("ct-bucket-updated");
    }

    @AfterAll
    static void cleanup() {
        if (cloudTrail != null) {
            try {
                cloudTrail.deleteTrail(r -> r.name(trailName));
            } catch (Exception ignored) {
            }
            cloudTrail.close();
        }
    }

    @Test
    @Order(1)
    void createTrail() {
        CreateTrailResponse response = cloudTrail.createTrail(r -> r
                .name(trailName)
                .s3BucketName(bucketName)
                .includeGlobalServiceEvents(true)
                .isMultiRegionTrail(true));

        assertThat(response.name()).isEqualTo(trailName);
        assertThat(response.s3BucketName()).isEqualTo(bucketName);
        assertThat(response.trailARN()).contains(":cloudtrail:").endsWith(":trail/" + trailName);
        assertThat(response.includeGlobalServiceEvents()).isTrue();
        assertThat(response.isMultiRegionTrail()).isTrue();
    }

    @Test
    @Order(2)
    void describeTrailsByName() {
        DescribeTrailsResponse response = cloudTrail.describeTrails(r -> r.trailNameList(trailName));

        assertThat(response.trailList()).hasSize(1);
        Trail trail = response.trailList().get(0);
        assertThat(trail.name()).isEqualTo(trailName);
        assertThat(trail.homeRegion()).isEqualTo("us-east-1");
        assertThat(trail.s3BucketName()).isEqualTo(bucketName);
    }

    @Test
    @Order(3)
    void updateTrail() {
        UpdateTrailResponse response = cloudTrail.updateTrail(r -> r
                .name(trailName)
                .s3BucketName(updatedBucketName)
                .includeGlobalServiceEvents(false));

        assertThat(response.name()).isEqualTo(trailName);
        assertThat(response.s3BucketName()).isEqualTo(updatedBucketName);
        assertThat(response.includeGlobalServiceEvents()).isFalse();
    }

    @Test
    @Order(4)
    void startAndStopLogging() {
        cloudTrail.startLogging(r -> r.name(trailName));

        GetTrailStatusResponse started = cloudTrail.getTrailStatus(r -> r.name(trailName));
        assertThat(started.isLogging()).isTrue();
        assertThat(started.latestDeliveryTime()).isNull();

        cloudTrail.stopLogging(r -> r.name(trailName));

        GetTrailStatusResponse stopped = cloudTrail.getTrailStatus(r -> r.name(trailName));
        assertThat(stopped.isLogging()).isFalse();
    }

    @Test
    @Order(5)
    void putEventSelectorsAcceptsTrailName() {
        cloudTrail.putEventSelectors(r -> r.trailName(trailName));
    }

    @Test
    @Order(6)
    void deleteTrail() {
        cloudTrail.deleteTrail(r -> r.name(trailName));

        assertThatThrownBy(() -> cloudTrail.getTrailStatus(r -> r.name(trailName)))
                .isInstanceOf(TrailNotFoundException.class);
    }

    @Test
    @Order(7)
    void lookupEventsReadsActualSsmCallsWithoutATrail() {
        String name = "/cloudtrail/" + TestFixtures.uniqueName("history");
        java.time.Instant start = java.time.Instant.now().minusSeconds(1);
        try (var ssm = TestFixtures.ssmClient()) {
            try {
                ssm.putParameter(r -> r.name(name).type("String").value("private-value"));
                var events = cloudTrail.lookupEvents(r -> r.startTime(start).maxResults(50)
                        .lookupAttributes(a -> a.attributeKey("EventName").attributeValue("PutParameter"))).events();
                assertThat(events).anySatisfy(event -> {
                    assertThat(event.eventSource()).isEqualTo("ssm.amazonaws.com");
                    assertThat(event.eventTime()).isNotNull();
                    assertThat(event.eventId()).isNotBlank();
                    assertThat(event.cloudTrailEvent()).contains(name).doesNotContain("private-value");
                });
            } finally {
                ssm.deleteParameter(r -> r.name(name));
            }
        }
        assertThatThrownBy(() -> cloudTrail.lookupEvents(r -> r.maxResults(51)))
                .isInstanceOf(software.amazon.awssdk.services.cloudtrail.model.InvalidMaxResultsException.class);
    }

    @Test
    @Order(8)
    void listPublicKeysDoesNotInventDigestSigningKeys() {
        assertThat(cloudTrail.listPublicKeys(r -> {}).publicKeyList()).isEmpty();
        assertThatThrownBy(() -> cloudTrail.listPublicKeys(r -> r
                .startTime(java.time.Instant.ofEpochSecond(2)).endTime(java.time.Instant.ofEpochSecond(1))))
                .isInstanceOf(software.amazon.awssdk.services.cloudtrail.model.InvalidTimeRangeException.class);
    }

    @Test
    @Order(9)
    void eventDataStoreControlPlaneHasTypedErrorsAndStableIdentity() {
        String name = TestFixtures.uniqueName("lake-sdk");
        String arn = cloudTrail.createEventDataStore(r -> r.name(name).retentionPeriod(7)
                .multiRegionEnabled(false).startIngestion(false).terminationProtectionEnabled(false))
                .eventDataStoreArn();
        try {
            var observed = cloudTrail.getEventDataStore(r -> r.eventDataStore(arn));
            assertThat(observed.name()).isEqualTo(name);
            assertThat(observed.statusAsString()).isEqualTo("STOPPED_INGESTION");
            assertThat(cloudTrail.listEventDataStoresPaginator(r -> {}).stream()
                    .flatMap(page -> page.eventDataStores().stream()).toList())
                    .anySatisfy(store -> assertThat(store.eventDataStoreArn()).isEqualTo(arn));
            var updated = cloudTrail.updateEventDataStore(r -> r.eventDataStore(arn).retentionPeriod(14));
            assertThat(updated.eventDataStoreArn()).isEqualTo(arn);
            assertThat(updated.retentionPeriod()).isEqualTo(14);
            assertThatThrownBy(() -> cloudTrail.createEventDataStore(r -> r.name(name)))
                    .isInstanceOf(software.amazon.awssdk.services.cloudtrail.model.EventDataStoreAlreadyExistsException.class);
        } finally {
            cloudTrail.deleteEventDataStore(r -> r.eventDataStore(arn));
        }
        assertThat(cloudTrail.getEventDataStore(r -> r.eventDataStore(arn)).statusAsString()).isEqualTo("PENDING_DELETION");
        assertThatThrownBy(() -> cloudTrail.getEventDataStore(r -> r
                .eventDataStore("00000000-0000-0000-0000-000000000000")))
                .isInstanceOf(software.amazon.awssdk.services.cloudtrail.model.EventDataStoreNotFoundException.class);
    }
}
