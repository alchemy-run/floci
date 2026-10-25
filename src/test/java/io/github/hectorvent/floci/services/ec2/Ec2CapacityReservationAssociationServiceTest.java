package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class Ec2CapacityReservationAssociationServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String FILE = "ec2-capacity-reservations.json";

    @TempDir
    Path directory;

    @Test
    void unknownZoneIdIsRejectedBeforeReservationPublication() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            AwsException failure = assertThrows(AwsException.class, () ->
                    fixture.service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", null,
                            "use1-az99", 1, null, null, null, null, null, null, null, null));
            assertEquals("InvalidParameterValue", failure.getErrorCode());
            assertTrue(fixture.service.describeCapacityReservations(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void legacyUnknownZoneDoesNotBlockLaunchOrRestart(String mode) {
        String invalidId;
        try (Fixture fixture = new Fixture(mode, directory)) {
            CapacityReservation invalid = fixture.create(1);
            invalid.setAvailabilityZoneId("use1-az99");
            invalidId = invalid.getCapacityReservationId();
            fixture.store.put(REGION + "::" + invalidId, invalid);
        }
        try (Fixture recovered = new Fixture(mode, directory)) {
            Instance instance = recovered.launch();
            assertEquals(null, recovered.service.instanceCapacityReservationId(instance));
            recovered.service.stopInstances(REGION, List.of(instance.getInstanceId()));
            recovered.service.startInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(null, recovered.service.instanceCapacityReservationId(instance));
            CapacityReservation invalid = recovered.service.describeCapacityReservations(REGION,
                    List.of(invalidId), Map.of()).getFirst();
            assertEquals(1, invalid.getAvailableInstanceCount());
            assertTrue(invalid.getInstanceIds().isEmpty());
        }
    }

    @Test
    void newOpenReservationAdoptsOnlyEligibleUnassociatedRunningInstances() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instance open = fixture.launchWithPreference("open");
            Instance excluded = fixture.launchWithPreference("none");
            fixture.service.describeInstances(REGION,
                    List.of(open.getInstanceId(), excluded.getInstanceId()), Map.of());
            CapacityReservation first = fixture.create(2);
            CapacityReservation second = fixture.create(2);

            assertEquals(List.of(open.getInstanceId()), fixture.describe(first).getInstanceIds());
            assertEquals(1, fixture.describe(first).getAvailableInstanceCount());
            assertEquals(2, fixture.describe(second).getAvailableInstanceCount());
            assertEquals(first.getCapacityReservationId(), fixture.service.instanceCapacityReservationId(open));
            assertEquals(null, fixture.service.instanceCapacityReservationId(excluded));
            fixture.service.terminateInstances(REGION, List.of(open.getInstanceId(), excluded.getInstanceId()));
            assertEquals(2, fixture.describe(first).getAvailableInstanceCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void adoptedRunningMembershipSurvivesRestart(String mode) {
        CapacityReservation capacity;
        String instanceId;
        try (Fixture fixture = new Fixture(mode, directory)) {
            instanceId = fixture.launch().getInstanceId();
            fixture.service.describeInstances(REGION, List.of(instanceId), Map.of());
            capacity = fixture.create(2);
            assertEquals(List.of(instanceId), fixture.describe(capacity).getInstanceIds());
        }
        try (Fixture restarted = new Fixture(mode, directory)) {
            assertEquals(List.of(instanceId), restarted.describe(capacity).getInstanceIds());
            assertEquals(1, restarted.describe(capacity).getAvailableInstanceCount());
            restarted.service.stopInstances(REGION, List.of(instanceId));
            assertTrue(restarted.describe(capacity).getInstanceIds().isEmpty());
            assertEquals(2, restarted.describe(capacity).getAvailableInstanceCount());
        }
    }

    @Test
    void failedReleaseRetainsMembershipUntilRetryReconcilesPersistedStop() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            CapacityReservation source = fixture.create(1);
            Instance instance = fixture.launch();
            doThrow(new IllegalStateException("injected release failure")).when(fixture.store).putAllAtomically(any());
            assertThrows(AwsException.class,
                    () -> fixture.service.stopInstances(REGION, List.of(instance.getInstanceId())));
            assertEquals(0, fixture.store.get(REGION + "::" + source.getCapacityReservationId())
                    .orElseThrow().getAvailableInstanceCount());
            reset(fixture.store);
            assertEquals(1, fixture.describe(source).getAvailableInstanceCount());
            assertTrue(fixture.describe(source).getInstanceIds().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionSerializesWithCancellation(boolean restart) throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CapacityReservation source = fixture.create(1);
            CapacityReservation destination = fixture.create(1);
            Instance stopped = restart ? fixture.launch() : null;
            if (stopped != null) {
                fixture.service.stopInstances(REGION, List.of(stopped.getInstanceId()));
            }
            CountDownLatch beforePublish = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            AtomicReference<String> acquiredId = new AtomicReference<>();
            doAnswer(invocation -> {
                Map<String, CapacityReservation> batch = invocation.getArgument(0);
                acquiredId.compareAndSet(null, batch.values().iterator().next().getCapacityReservationId());
                beforePublish.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.store).putAllAtomically(any());
            Future<Instance> admission = workers.submit(() -> {
                if (stopped == null) {
                    return fixture.launch();
                }
                fixture.service.startInstances(REGION, List.of(stopped.getInstanceId()));
                return stopped;
            });
            try {
                assertTrue(beforePublish.await(10, TimeUnit.SECONDS));
                CapacityReservation admittedSource = source.getCapacityReservationId().equals(acquiredId.get())
                        ? source : destination;
                CapacityReservation other = admittedSource == source ? destination : source;
                AtomicReference<Thread> mutationThread = new AtomicReference<>();
                Future<?> mutation = workers.submit(() -> {
                    mutationThread.set(Thread.currentThread());
                    fixture.service.cancelCapacityReservation(REGION, admittedSource.getCapacityReservationId());
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> mutation.isDone()
                        || mutationThread.get() != null && mutationThread.get().getState() == Thread.State.BLOCKED);
                assertTrue(!mutation.isDone(), "Mutation must wait for atomic admission publication");
                resume.countDown();
                Instance instance = admission.get(10, TimeUnit.SECONDS);
                mutation.get(10, TimeUnit.SECONDS);
                CapacityReservation currentSource = fixture.describe(admittedSource);
                CapacityReservation currentDestination = fixture.describe(other);
                assertTrue(currentSource.getInstanceIds().isEmpty());
                assertEquals(null, fixture.service.instanceCapacityReservationId(instance));
                assertEquals(1, currentDestination.getAvailableInstanceCount());
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void rejectedLaunchAfterCancellationReclaimsItsPublishedResources() throws Exception {
        try (Fixture fixture = new Fixture("persistent", directory);
                ExecutorService workers = Executors.newSingleThreadExecutor()) {
            CapacityReservation source = fixture.create(1);
            CapacityReservation destination = fixture.create(1);
            CountDownLatch beforeAdmission = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            doAnswer(invocation -> {
                beforeAdmission.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.service).acquireCapacity(eq(REGION), any());
            Future<Instance> launch = workers.submit(() -> fixture.launch(source.getCapacityReservationId()));
            try {
                assertTrue(beforeAdmission.await(10, TimeUnit.SECONDS));
                fixture.service.cancelCapacityReservation(REGION, source.getCapacityReservationId());
                resume.countDown();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> launch.get(10, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof AwsException);
                assertTrue(fixture.describe(source).getInstanceIds().isEmpty());
                assertTrue(fixture.describe(destination).getInstanceIds().isEmpty());
                assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                        .flatMap(reservation -> reservation.getInstances().stream())
                        .allMatch(instance -> "terminated".equals(instance.getState().getName())));
                assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void failedLaunchMembershipPublicationReleasesOwnedVolumeAndInstanceCapacity() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            CapacityReservation source = fixture.create(1);
            doThrow(new IllegalStateException("injected admission failure"))
                    .when(fixture.store).putAllAtomically(any());

            AwsException failure = assertThrows(AwsException.class, fixture::launch);

            assertEquals("InternalError", failure.getErrorCode());
            reset(fixture.store);
            assertEquals(1, fixture.describe(source).getAvailableInstanceCount());
            assertTrue(fixture.describe(source).getInstanceIds().isEmpty());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .allMatch(instance -> "terminated".equals(instance.getState().getName())));
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void targetedAdmissionLaunchesOnlyTheAvailableCapacity(boolean enforceQuotas) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            when(fixture.config.services().ec2().enforceVcpuQuotas()).thenReturn(enforceQuotas);
            when(fixture.config.services().servicequotas().standardOnDemandVcpus()).thenReturn(20);
            CapacityReservation capacity = fixture.create(2);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationId(capacity.getCapacityReservationId());
            specification.setCapacityReservationTarget(target);
            List<Instance> instances = fixture.service.runInstances(REGION, "ami-test", "t3.micro", 1, 3,
                    null, List.of(), null, null, List.of(), null, null, null, null, 0, null, null, null,
                    null, false, null, null, null, false, null, specification).getInstances();
            assertEquals(2, instances.size());
            assertEquals(0, fixture.describe(capacity).getAvailableInstanceCount());
            assertEquals(2, fixture.describe(capacity).getInstanceIds().size());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .noneMatch(instance -> "terminated".equals(instance.getState().getName())));
        }
    }

    @Test
    void targetedMismatchKeepsItsExistingErrorAndFreeCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.service.createCapacityReservation(REGION, "t3.small",
                    "Linux/UNIX", REGION + "a", null, 1, null, null, null, null, null, null, null, null);
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launch(capacity.getCapacityReservationId()));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
            assertTrue(fixture.describe(capacity).getInstanceIds().isEmpty());
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @Test
    void targetedShortageRejectsLaunchWithoutPublishingResources() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            Instance admitted = fixture.launch(capacity.getCapacityReservationId());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launch(capacity.getCapacityReservationId()));
            assertEquals("ReservationCapacityExceeded", failure.getErrorCode());
            assertEquals("The targeted Capacity Reservation has insufficient available capacity.", failure.getMessage());
            assertEquals(List.of(admitted.getInstanceId()), fixture.describe(capacity).getInstanceIds());
            assertEquals(1, fixture.service.describeVolumes(REGION, List.of(), Map.of()).size());
        }
    }

    @Test
    void targetedShortageDuringAdmissionReclaimsFailedLaunch() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            AtomicReference<Instance> winner = new AtomicReference<>();
            doAnswer(invocation -> {
                reset(fixture.service);
                winner.set(fixture.launch(capacity.getCapacityReservationId()));
                return invocation.callRealMethod();
            }).when(fixture.service).acquireCapacity(eq(REGION), any());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launch(capacity.getCapacityReservationId()));
            assertEquals("ReservationCapacityExceeded", failure.getErrorCode());
            assertEquals("The targeted Capacity Reservation has insufficient available capacity.", failure.getMessage());
            assertEquals(List.of(winner.get().getInstanceId()), fixture.describe(capacity).getInstanceIds());
            assertEquals(1, fixture.service.describeVolumes(REGION, List.of(), Map.of()).size());
            assertEquals(1, fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .filter(instance -> !"terminated".equals(instance.getState().getName())).count());
        }
    }

    @Test
    void targetedShortageOnRestartPreservesStoppedInstanceAndOccupant() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            Instance stopped = fixture.launch(capacity.getCapacityReservationId());
            fixture.service.stopInstances(REGION, List.of(stopped.getInstanceId()));
            Instance occupant = fixture.launch(capacity.getCapacityReservationId());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.service.startInstances(REGION, List.of(stopped.getInstanceId())));
            assertEquals("ReservationCapacityExceeded", failure.getErrorCode());
            assertEquals("The targeted Capacity Reservation has insufficient available capacity.", failure.getMessage());
            assertEquals(List.of(occupant.getInstanceId()), fixture.describe(capacity).getInstanceIds());
            assertEquals("stopped", fixture.service.describeInstances(REGION,
                    List.of(stopped.getInstanceId()), Map.of()).getFirst().getInstances().getFirst().getState().getName());
        }
    }

    @Test
    void reservationOnlyLaunchDoesNotFallBackToOnDemand() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launchWithPreference("capacity-reservations-only"));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).isEmpty());
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @Test
    void reservationOnlyLaunchConsumesAndReleasesOpenCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            Instance instance = fixture.launchWithPreference("capacity-reservations-only");
            assertEquals(capacity.getCapacityReservationId(),
                    fixture.service.instanceCapacityReservationId(instance));
            assertEquals(0, fixture.describe(capacity).getAvailableInstanceCount());
            fixture.service.stopInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
            fixture.service.startInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(0, fixture.describe(capacity).getAvailableInstanceCount());
            fixture.service.terminateInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
        }
    }

    @Test
    void reservationOnlyRestartCannotFallBackAfterCapacityIsCancelled() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            Instance instance = fixture.launchWithPreference("capacity-reservations-only");
            fixture.service.stopInstances(REGION, List.of(instance.getInstanceId()));
            fixture.service.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.service.startInstances(REGION, List.of(instance.getInstanceId())));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertEquals("stopped", fixture.service.describeInstances(REGION,
                    List.of(instance.getInstanceId()), Map.of()).getFirst().getInstances().getFirst()
                    .getState().getName());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reservationOnlyAdmissionCapsAtMatchingOpenCapacity(boolean enforceQuotas) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            when(fixture.config.services().ec2().enforceVcpuQuotas()).thenReturn(enforceQuotas);
            when(fixture.config.services().servicequotas().standardOnDemandVcpus()).thenReturn(20);
            CapacityReservation first = fixture.create(1);
            CapacityReservation second = fixture.create(1);
            fixture.service.createCapacityReservation(REGION, "t3.small", "Linux/UNIX", REGION + "a",
                    null, 5, null, null, null, null, null, null, null, null);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference("capacity-reservations-only");
            List<Instance> instances = fixture.service.runInstances(REGION, "ami-test", "t3.micro", 1, 3,
                    null, List.of(), null, null, List.of(), null, null, null, null, 0, null, null, null,
                    null, false, null, null, null, false, null, specification).getInstances();
            assertEquals(2, instances.size());
            assertEquals(0, fixture.describe(first).getAvailableInstanceCount());
            assertEquals(0, fixture.describe(second).getAvailableInstanceCount());
        }
    }

    @Test
    void reservationOnlyRechecksCapacityAtAdmission() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            doAnswer(invocation -> {
                fixture.service.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
                return invocation.callRealMethod();
            }).when(fixture.service).acquireCapacity(eq(REGION), any());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launchWithPreference("capacity-reservations-only"));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .allMatch(instance -> "terminated".equals(instance.getState().getName())));
        }
    }

    @Test
    void reservationOnlySpotLaunchIsRejectedBeforeMutation() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            fixture.create(1);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference("capacity-reservations-only");
            AwsException failure = assertThrows(AwsException.class, () -> fixture.service.runInstances(
                    REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null, List.of(), null,
                    null, null, null, 0, null, null, null, null, false, null, null, null, true, null, specification));
            assertEquals("InvalidParameterCombination", failure.getErrorCode());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @Test
    void reservationOnlyInsufficientMinimumDoesNotConsumeCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference("capacity-reservations-only");
            AwsException failure = assertThrows(AwsException.class, () -> fixture.service.runInstances(
                    REGION, "ami-test", "t3.micro", 2, 3, null, List.of(), null, null, List.of(), null,
                    null, null, null, 0, null, null, null, null, false, null, null, null, false, null, specification));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final EmulatorConfig config;
        private final StorageFactory factory;
        private final AccountAwareStorageBackend<CapacityReservation> store;
        private final Ec2Service service;

        private Fixture(String mode, Path path) {
            config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.services().ec2().mock()).thenReturn(true);
            when(config.services().ec2().mockPublicAddresses())
                    .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            when(config.storage().persistentPath()).thenReturn(path.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(3600000L);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ec2")).thenReturn(mode);
            when(access.storageFlushInterval("ec2")).thenReturn(3600000L);
            factory = new StorageFactory(config, access);
            store = spy(factory.create("ec2", FILE, new TypeReference<Map<String, CapacityReservation>>() {}));
            StorageFactory serviceFactory = spy(factory);
            doReturn(store).when(serviceFactory).create(eq("ec2"), eq(FILE), any());
            Ec2ImageCatalog catalog = new Ec2ImageCatalog();
            service = spy(new Ec2Service(config, null, null, new AmiImageResolver(catalog),
                    catalog, new Ec2InstanceTypeCatalog(), serviceFactory));
        }

        private Instance launch() {
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1,
                    null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        }

        private Instance launch(String targetId) {
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationId(targetId);
            specification.setCapacityReservationTarget(target);
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                    List.of(), null, null, null, null, 0, null, null, null, null, false, null, null, null,
                    false, null, specification).getInstances().getFirst();
        }

        private Instance launchWithPreference(String preference) {
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference(preference);
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                    List.of(), null, null, null, null, 0, null, null, null, null, false, null, null, null,
                    false, null, specification).getInstances().getFirst();
        }

        private CapacityReservation create(int count) {
            return service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", REGION + "a",
                    null, count, null, null, null, null, null, null, null, null);
        }

        private CapacityReservation describe(CapacityReservation reservation) {
            return service.describeCapacityReservations(REGION,
                    List.of(reservation.getCapacityReservationId()), Map.of()).getFirst();
        }

        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}
