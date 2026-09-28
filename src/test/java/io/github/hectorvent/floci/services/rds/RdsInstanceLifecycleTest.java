package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstancePendingModifiedValues;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RdsInstanceLifecycleTest {

    // 2026-09-21 is a Monday.
    private static final Instant MONDAY_NOON = Instant.parse("2026-09-21T12:00:00Z");

    @Test
    void aNewInstanceReportsCreatingThenAvailable() {
        DbInstance instance = available();
        RdsInstanceLifecycle.beginCreating(instance, MONDAY_NOON);

        assertEquals("creating", RdsInstanceLifecycle.reportedStatus(instance, "available", MONDAY_NOON));
        assertEquals("available", RdsInstanceLifecycle.reportedStatus(
                instance, "available", MONDAY_NOON.plus(RdsInstanceLifecycle.CREATING_PERIOD)));
    }

    @Test
    void aStorageChangeReportsModifyingThenStorageOptimizationThenAvailable() {
        DbInstance instance = available();
        RdsInstanceLifecycle.beginStorageModification(instance, MONDAY_NOON);

        assertEquals("modifying", RdsInstanceLifecycle.reportedStatus(instance, "available", MONDAY_NOON));
        Instant optimizing = MONDAY_NOON.plus(RdsInstanceLifecycle.MODIFYING_PERIOD);
        assertEquals("storage-optimization", RdsInstanceLifecycle.reportedStatus(instance, "available", optimizing));
        assertTrue(RdsInstanceLifecycle.storageOptimizing(instance, optimizing));
        Instant done = optimizing.plus(RdsInstanceLifecycle.STORAGE_OPTIMIZATION_PERIOD);
        assertEquals("available", RdsInstanceLifecycle.reportedStatus(instance, "available", done));
        assertFalse(RdsInstanceLifecycle.storageOptimizing(instance, done));
    }

    @Test
    void anInstanceThatIsNotAvailableReportsItsOwnStatus() {
        DbInstance instance = available();
        RdsInstanceLifecycle.beginCreating(instance, MONDAY_NOON);
        instance.setStatus(DbInstanceStatus.STOPPED);

        assertEquals("stopped", RdsInstanceLifecycle.reportedStatus(instance, "stopped", MONDAY_NOON));
    }

    @Test
    void theMaintenanceWindowStartIsFoundInTheFollowingWeekToo() {
        String tuesday = "tue:03:00-tue:04:00";
        assertFalse(RdsInstanceLifecycle.maintenanceWindowStartedBetween(
                tuesday, MONDAY_NOON, MONDAY_NOON.plus(Duration.ofHours(14))));
        assertTrue(RdsInstanceLifecycle.maintenanceWindowStartedBetween(
                tuesday, MONDAY_NOON, MONDAY_NOON.plus(Duration.ofHours(15))));

        String sundayNight = "sun:23:30-mon:00:30";
        Instant wednesday = MONDAY_NOON.plus(Duration.ofDays(2));
        assertFalse(RdsInstanceLifecycle.maintenanceWindowStartedBetween(
                sundayNight, wednesday, wednesday.plus(Duration.ofDays(3))));
        assertTrue(RdsInstanceLifecycle.maintenanceWindowStartedBetween(
                sundayNight, wednesday, wednesday.plus(Duration.ofDays(5))));
    }

    @Test
    void queuedModificationsAreDueOnceTheWindowHasStarted() {
        DbInstance instance = available();
        instance.setPreferredMaintenanceWindow("tue:03:00-tue:04:00");
        DbInstancePendingModifiedValues pending = new DbInstancePendingModifiedValues();
        pending.setBackupRetentionPeriod(1);
        pending.setQueuedAt(MONDAY_NOON);
        instance.setPendingModifiedValues(pending);

        assertFalse(RdsInstanceLifecycle.pendingModificationsDue(instance, MONDAY_NOON.plus(Duration.ofHours(1))));
        assertTrue(RdsInstanceLifecycle.pendingModificationsDue(instance, MONDAY_NOON.plus(Duration.ofDays(1))));
    }

    private static DbInstance available() {
        DbInstance instance = new DbInstance();
        instance.setStatus(DbInstanceStatus.AVAILABLE);
        return instance;
    }
}
