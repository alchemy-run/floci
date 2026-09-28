package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.BackupWindows;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstancePendingModifiedValues;
import io.github.hectorvent.floci.services.rds.model.DbInstanceSettings;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * The time-dependent parts of a DB instance's lifecycle: the transitional statuses RDS reports
 * while an operation runs, the storage-optimization period after a storage change, and the
 * maintenance window that applies queued modifications.
 *
 * <p>Floci completes creation and modification at once, but AWS reports "creating" and
 * "modifying" while they run and "storage-optimization" after a storage change, and clients
 * poll through those statuses. Each is reported for a short, configurable period after the
 * operation so a poller observes the same sequence it would on AWS without waiting as long.
 */
final class RdsInstanceLifecycle {

    static final String CREATING = "creating";
    static final String MODIFYING = "modifying";
    static final String STORAGE_OPTIMIZATION = "storage-optimization";
    static final String PENDING_REBOOT = "pending-reboot";
    static final String IN_SYNC = "in-sync";

    static final Duration CREATING_PERIOD = Duration.ofSeconds(1);
    static final Duration MODIFYING_PERIOD = Duration.ofSeconds(1);
    static final Duration STORAGE_OPTIMIZATION_PERIOD = Duration.ofSeconds(2);

    /** How long each transitional status is reported; configured by {@code floci.services.rds.*-status-millis}. */
    record Periods(Duration creating, Duration modifying, Duration storageOptimization) {
        static final Periods DEFAULT = new Periods(CREATING_PERIOD, MODIFYING_PERIOD, STORAGE_OPTIMIZATION_PERIOD);

        static Periods ofMillis(long creating, long modifying, long storageOptimization) {
            return new Periods(Duration.ofMillis(Math.max(0, creating)), Duration.ofMillis(Math.max(0, modifying)),
                    Duration.ofMillis(Math.max(0, storageOptimization)));
        }
    }

    private RdsInstanceLifecycle() {}

    /**
     * The DBInstanceStatus to report. Only an instance whose stored status is available reports a
     * transitional status; stopped, deleting and failed instances report their own.
     */
    static String reportedStatus(DbInstance instance, String storedLabel, Instant now) {
        if (instance.getStatus() != DbInstanceStatus.AVAILABLE) {
            return storedLabel;
        }
        if (instance.getTransitionalStatus() != null && instance.getTransitionalStatusUntil() != null
                && now.isBefore(instance.getTransitionalStatusUntil())) {
            return instance.getTransitionalStatus();
        }
        if (storageOptimizing(instance, now)) {
            return STORAGE_OPTIMIZATION;
        }
        return storedLabel;
    }

    static void beginCreating(DbInstance instance, Instant now) {
        beginCreating(instance, now, Periods.DEFAULT);
    }

    static void beginCreating(DbInstance instance, Instant now, Periods periods) {
        instance.setTransitionalStatus(CREATING);
        instance.setTransitionalStatusUntil(now.plus(periods.creating()));
    }

    static void beginStorageModification(DbInstance instance, Instant now) {
        beginStorageModification(instance, now, Periods.DEFAULT);
    }

    /** A storage change is modified first, then optimized while the instance stays available. */
    static void beginStorageModification(DbInstance instance, Instant now, Periods periods) {
        instance.setTransitionalStatus(MODIFYING);
        instance.setTransitionalStatusUntil(now.plus(periods.modifying()));
        instance.setStorageOptimizationUntil(now.plus(periods.modifying()).plus(periods.storageOptimization()));
    }

    static void clearTransitions(DbInstance instance) {
        instance.setTransitionalStatus(null);
        instance.setTransitionalStatusUntil(null);
        instance.setStorageOptimizationUntil(null);
    }

    /** RDS refuses another storage change until the previous one has been optimized. */
    static boolean storageOptimizing(DbInstance instance, Instant now) {
        return instance.getStorageOptimizationUntil() != null
                && now.isBefore(instance.getStorageOptimizationUntil());
    }

    /**
     * Whether the instance's queued modifications are due: a maintenance window has started
     * since the oldest of them was queued.
     */
    static boolean pendingModificationsDue(DbInstance instance, Instant now) {
        DbInstancePendingModifiedValues pending = instance.getPendingModifiedValues();
        if (pending == null || !pending.hasChanges() || pending.getQueuedAt() == null) {
            return false;
        }
        String window = instance.getPreferredMaintenanceWindow() != null
                ? instance.getPreferredMaintenanceWindow()
                : DbInstanceSettings.DEFAULT_MAINTENANCE_WINDOW;
        return maintenanceWindowStartedBetween(window, pending.getQueuedAt(), now);
    }

    /** Whether the weekly window {@code ddd:hh24:mi-ddd:hh24:mi} (UTC) starts in {@code (from, to]}. */
    static boolean maintenanceWindowStartedBetween(String window, Instant from, Instant to) {
        int startMinuteOfWeek = BackupWindows.parseMaintenanceWindow(window)[0];
        LocalDate monday = from.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant start = monday.atStartOfDay(ZoneOffset.UTC).toInstant()
                .plus(Duration.ofMinutes(startMinuteOfWeek));
        if (!start.isAfter(from)) {
            start = start.plus(Duration.ofDays(7));
        }
        return !start.isAfter(to);
    }
}
