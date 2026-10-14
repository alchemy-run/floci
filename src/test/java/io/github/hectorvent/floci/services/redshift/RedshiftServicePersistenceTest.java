package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

class RedshiftServicePersistenceTest {
    @TempDir
    Path directory;

    private record Fixture(StorageFactory storage, RedshiftService service, SnsService sns) implements AutoCloseable {
        @Override
        public void close() {
            storage.shutdownAll();
        }
    }

    private Fixture open(String account, String region) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(account);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshift")).thenReturn("persistent");
        StorageFactory storage = new StorageFactory(config, access);
        SnsService sns = mock(SnsService.class);
        RedshiftService service = new RedshiftService(storage, mock(RedshiftContainerManager.class), config,
                new RegionResolver(region, account), mock(RedshiftProxyManager.class), mock(DockerHostResolver.class),
                new RedshiftCredentialBroker(), mock(Ec2Service.class), sns);
        return new Fixture(storage, service, sns);
    }

    @Test
    void subscriptionsRejectInvalidFiltersAndOnlyPublishCommittedEvents() {
        String topic = "arn:aws:sns:us-east-1:111111111111:redshift-events";
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            assertEquals("SourceNotFound", assertThrows(AwsException.class, () ->
                    fixture.service().createEventSubscription("missing-source", topic, "cluster", List.of("missing"),
                            List.of(), null, null, Map.of())).getErrorCode());
            assertEquals("SubscriptionSeverityNotFound", assertThrows(AwsException.class, () ->
                    fixture.service().createEventSubscription("bad-severity", topic, null, List.of(),
                            List.of(), "WARNING", null, Map.of())).getErrorCode());
            assertEquals("SubscriptionCategoryNotFound", assertThrows(AwsException.class, () ->
                    fixture.service().createEventSubscription("bad-category", topic, null, List.of(),
                            List.of("unrecognized"), null, null, Map.of())).getErrorCode());
            assertTrue(fixture.service().describeEventSubscriptions(null, null, null, List.of(), List.of()).items().isEmpty());
            fixture.service().createEventSubscription("events", topic, "cluster-parameter-group", List.of(),
                    List.of("management"), "INFO", true, Map.of());
            assertThrows(AwsException.class, () -> fixture.service().modifyClusterParameterGroup("missing",
                    List.of(new Parameter("statement_timeout", "1"))));
            verify(fixture.sns(), never()).publish(any(), any(), any(), any(), any());
            when(fixture.sns().publish(any(), any(), any(), any(), any()))
                    .thenThrow(new AwsException("InternalError", "Delivery unavailable", 500));
            fixture.service().createClusterParameterGroup("committed", "redshift-1.0", "delivery failure");
            assertEquals("committed", fixture.service().describeClusterParameterGroups("committed").getFirst().getParameterGroupName());
            assertEquals(1, fixture.service().describeEvents("committed", "cluster-parameter-group", null, null,
                    60, null, null).events().size());
            verify(fixture.sns()).publish(any(), any(), any(), any(), any());
        }
    }

    @Test
    void subscriptionsPersistFiltersTagsPaginationIsolationAndDeletion() {
        String topic = "arn:aws:sns:us-east-1:111111111111:redshift-events";
        String arn = "arn:aws:redshift:us-east-1:111111111111:eventsubscription:subscription-0";
        String created;
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            for (int i = 0; i < 21; i++) {
                fixture.service().createEventSubscription("subscription-" + i, topic, "cluster", List.of(),
                        List.of("management", "monitoring"), "INFO", true, Map.of("owner", "fixture"));
            }
            created = fixture.service().describeEventSubscriptions("subscription-0", null, null, List.of(), List.of())
                    .items().getFirst().creationTime();
            assertEquals("SubscriptionAlreadyExist", assertThrows(AwsException.class, () ->
                    fixture.service().createEventSubscription("subscription-0", topic, null, List.of(), List.of(),
                            null, true, Map.of())).getErrorCode());
        }
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            RedshiftService.Page<EventSubscription> first = fixture.service().describeEventSubscriptions(null, 20, null,
                    List.of("owner"), List.of("fixture"));
            assertEquals(20, first.items().size());
            assertNotNull(first.marker());
            RedshiftService.Page<EventSubscription> last = fixture.service().describeEventSubscriptions(null, 20, first.marker(),
                    List.of("owner"), List.of("fixture"));
            assertEquals(1, last.items().size());
            assertNull(last.marker());
            assertTrue(first.items().stream().noneMatch(last.items()::contains));
            assertThrows(AwsException.class, () -> fixture.service().describeEventSubscriptions(null, 19, null, List.of(), List.of()));
            assertThrows(AwsException.class, () -> fixture.service().describeEventSubscriptions(null, 20, "bad", List.of(), List.of()));
            fixture.service().modifyEventSubscription("subscription-0", null, null, null, List.of("monitoring"), "ERROR", false);
            fixture.service().createTags(arn, Map.of("updated", "yes"));
            fixture.service().deleteTags(arn, List.of("owner"));
        }
        try (Fixture fixture = open("222222222222", "us-east-1")) {
            assertTrue(fixture.service().describeEventSubscriptions(null, null, null, List.of(), List.of()).items().isEmpty());
            assertThrows(AwsException.class, () -> fixture.service().deleteEventSubscription("subscription-0"));
        }
        try (Fixture fixture = open("111111111111", "us-west-2")) {
            assertTrue(fixture.service().describeEventSubscriptions(null, null, null, List.of(), List.of()).items().isEmpty());
            assertThrows(AwsException.class, () -> fixture.service().listTagsForResource(arn));
        }
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            EventSubscription subscription = fixture.service().describeEventSubscriptions("subscription-0", null, null,
                    List.of(), List.of()).items().getFirst();
            assertEquals(created, subscription.creationTime());
            assertEquals(topic, subscription.snsTopicArn());
            assertEquals("cluster", subscription.sourceType());
            assertEquals(List.of("monitoring"), subscription.eventCategories());
            assertEquals("ERROR", subscription.severity());
            assertEquals(false, subscription.enabled());
            assertEquals(Map.of("updated", "yes"), subscription.tags());
            assertEquals(subscription.tags(), fixture.service().listTagsForResource(arn));
            when(fixture.sns().getTopicAttributes(topic, "us-east-1"))
                    .thenThrow(new AwsException("NotFound", "Topic no longer exists", 404));
            assertEquals("topic-not-exist", fixture.service().describeEventSubscriptions("subscription-0", null, null,
                    List.of(), List.of()).items().getFirst().status());
            fixture.service().deleteEventSubscription("subscription-0");
        }
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            assertEquals("SubscriptionNotFound", assertThrows(AwsException.class, () ->
                    fixture.service().describeEventSubscriptions("subscription-0", null, null, List.of(), List.of())).getErrorCode());
        }
    }

    @Test
    void parameterSourcesResetTagsAndDeletionSurviveReload() {
        String name = "persisted-parameters";
        String arn = "arn:aws:redshift:us-east-1:111111111111:parametergroup:" + name;
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            fixture.service().createClusterParameterGroup(name, "redshift-1.0", "persisted");
            fixture.service().createTags(arn, Map.of("owner", "persisted"));
            fixture.service().modifyClusterParameterGroup(name, List.of(
                    new Parameter("enable_user_activity_logging", "true"), new Parameter("statement_timeout", "60000")));
        }
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            assertEquals(2, fixture.service().describeClusterParameters(name, "user").size());
            assertEquals("persisted", fixture.service().describeClusterParameterGroups(name).getFirst().getTags().get("owner"));
            fixture.service().resetClusterParameterGroup(name, false,
                    List.of(new Parameter("enable_user_activity_logging", null)));
        }
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            List<Parameter> overrides = fixture.service().describeClusterParameters(name, "user");
            assertEquals(1, overrides.size());
            assertEquals("statement_timeout", overrides.getFirst().getParameterName());
            assertEquals("60000", overrides.getFirst().getParameterValue());
            fixture.service().resetClusterParameterGroup(name, true, List.of());
        }
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            assertTrue(fixture.service().describeClusterParameters(name, "user").isEmpty());
            fixture.service().deleteClusterParameterGroup(name);
        }
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            assertEquals("ClusterParameterGroupNotFound", assertThrows(AwsException.class,
                    () -> fixture.service().describeClusterParameterGroups(name)).getErrorCode());
        }
    }

    @Test
    void eventsPersistPaginateAndRemainAccountAndRegionScoped() {
        String name = "event-pages";
        Instant start = Instant.now().minusSeconds(1);
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            fixture.service().createClusterParameterGroup(name, "redshift-1.0", "events");
            for (int i = 0; i < 25; i++) {
                fixture.service().modifyClusterParameterGroup(name, List.of(new Parameter("statement_timeout", String.valueOf(i))));
            }
        }
        Instant end = Instant.now().plusSeconds(1);
        try (Fixture fixture = open("111111111111", "us-east-1")) {
            RedshiftService.EventPage first = fixture.service().describeEvents(name, "cluster-parameter-group",
                    start, end, null, 20, null);
            assertEquals(20, first.events().size());
            assertNotNull(first.marker());
            RedshiftService.EventPage second = fixture.service().describeEvents(name, "cluster-parameter-group",
                    start, end, null, 20, first.marker());
            assertEquals(6, second.events().size());
            assertNull(second.marker());
            assertTrue(first.events().stream().noneMatch(second.events()::contains));
            assertTrue(fixture.service().describeEvents(name, "cluster-parameter-group", end, end.plusSeconds(1),
                    null, 20, null).events().isEmpty());
            assertThrows(AwsException.class, () -> fixture.service().describeEvents(name, "cluster-parameter-group",
                    start, end, null, 20, "invalid-marker"));
        }
        try (Fixture fixture = open("222222222222", "us-east-1")) {
            assertTrue(fixture.service().describeEvents(null, null, start, end, null, 20, null).events().isEmpty());
            assertThrows(AwsException.class, () -> fixture.service().describeClusterParameterGroups(name));
        }
        try (Fixture fixture = open("111111111111", "us-west-2")) {
            assertTrue(fixture.service().describeEvents(null, null, start, end, null, 20, null).events().isEmpty());
        }
    }
}
