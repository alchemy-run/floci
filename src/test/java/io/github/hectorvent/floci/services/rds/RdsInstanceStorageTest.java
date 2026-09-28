package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.RdsInstanceStorage.Request;
import io.github.hectorvent.floci.services.rds.RdsInstanceStorage.Storage;
import io.github.hectorvent.floci.services.rds.model.DbInstancePendingModifiedValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The storage rules the Alchemy DBInstance storage-coupling lifecycle pins against a live
 * account: gp3 baselines below and above the striping threshold, provisioned IOPS on io1 and io2,
 * and the defaults a request that leaves members out receives.
 */
class RdsInstanceStorageTest {

    @Test
    void createDefaultsToGp3WithItsFixedSmallVolumeBaseline() {
        assertEquals(new Storage(20, "gp3", 3000, 125),
                RdsInstanceStorage.forCreate("postgres", 20, null, null, null));
    }

    @Test
    void createWithIopsAndNoStorageTypeDefaultsToIo1() {
        assertEquals(new Storage(100, "io1", 1000, null),
                RdsInstanceStorage.forCreate("postgres", 100, null, 1000, null));
    }

    @Test
    void createAtTheStripingThresholdTakesTheStripedBaselineOrTheProvisionedValues() {
        assertEquals(new Storage(400, "gp3", 12000, 500),
                RdsInstanceStorage.forCreate("postgres", 400, "gp3", null, null));
        assertEquals(new Storage(400, "gp3", 16000, 750),
                RdsInstanceStorage.forCreate("postgres", 400, "gp3", 16000, 750));
        assertEquals(new Storage(200, "gp3", 12000, 500),
                RdsInstanceStorage.forCreate("oracle-ee", 200, "gp3", null, null));
    }

    @Test
    void smallGp3RefusesProvisionedPerformanceExceptOnSqlServer() {
        AwsException error = assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forCreate("postgres", 20, "gp3", 3000, 125));
        assertEquals("InvalidParameterCombination", error.getErrorCode());
        assertEquals(new Storage(20, "gp3", 4000, 200),
                RdsInstanceStorage.forCreate("sqlserver-se", 20, "gp3", 4000, 200));
    }

    @Test
    void gp2AndMagneticCarryNoPerformance() {
        assertEquals(new Storage(25, "gp2", null, null),
                RdsInstanceStorage.forCreate("postgres", 25, "gp2", null, null));
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forCreate("postgres", 25, "gp2", 1000, null)).getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forCreate("postgres", 100, "io1", 1000, 125)).getErrorCode());
    }

    @Test
    void provisionedIopsStorageRequiresIops() {
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forCreate("postgres", 100, "io2", null, null)).getErrorCode());
    }

    @Test
    void unknownStorageTypeIsRejected() {
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forCreate("postgres", 20, "ssd", null, null)).getErrorCode());
    }

    @Test
    void auroraInstancesReportTheClusterVolume() {
        assertEquals(new Storage(20, "aurora", null, null),
                RdsInstanceStorage.forCreate("aurora-postgresql", 20, "gp3", null, null));
    }

    @Test
    void removingGp2SelectsGp3WithItsBaseline() {
        Storage gp2 = new Storage(25, "gp2", null, null);
        assertEquals(new Storage(25, "gp3", 3000, 125),
                RdsInstanceStorage.forModify("postgres", gp2, new Request(25, "gp3", null, null)));
    }

    @Test
    void stripedGp3ResizeKeepsProvisionedPerformanceAndRemovalRestoresTheBaseline() {
        Storage provisioned = new Storage(400, "gp3", 16000, 750);
        assertEquals(new Storage(500, "gp3", 16000, 750),
                RdsInstanceStorage.forModify("postgres", provisioned, new Request(500, null, null, null)));
        assertEquals(new Storage(400, "gp3", 12000, 500),
                RdsInstanceStorage.forModify("postgres", provisioned, new Request(400, "gp3", 12000, 500)));
    }

    @Test
    void growingPastTheStripingThresholdRaisesTheBaseline() {
        Storage small = new Storage(300, "gp3", 3000, 125);
        assertEquals(new Storage(400, "gp3", 12000, 500),
                RdsInstanceStorage.forModify("postgres", small, new Request(400, null, null, null)));
    }

    @Test
    void gp3ThroughputIsAtMostAQuarterOfTheIops() {
        Storage striped = new Storage(400, "gp3", 12000, 500);
        assertEquals(new Storage(400, "gp3", 12000, 750),
                RdsInstanceStorage.forModify("postgres", striped, new Request(400, "gp3", 12000, 750)));
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forModify("postgres", striped,
                        new Request(400, "gp3", 12000, 3001))).getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forModify("postgres", striped,
                        new Request(400, "gp3", 11000, 500))).getErrorCode());
    }

    @Test
    void provisionedIopsSurviveAResizeAndCanBeLowered() {
        Storage io1 = new Storage(100, "io1", 3000, null);
        assertEquals(new Storage(120, "io1", 3000, null),
                RdsInstanceStorage.forModify("postgres", io1, new Request(120, null, null, null)));
        assertEquals(new Storage(100, "io1", 1000, null),
                RdsInstanceStorage.forModify("postgres", io1, new Request(100, "io1", 1000, null)));
    }

    @Test
    void storageCannotShrinkAndMustGrowByTenPercent() {
        Storage current = new Storage(100, "gp3", 3000, 125);
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forModify("postgres", current,
                        new Request(90, null, null, null))).getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> RdsInstanceStorage.forModify("postgres", current,
                        new Request(105, null, null, null))).getErrorCode());
        assertEquals(110, RdsInstanceStorage.forModify("postgres", current,
                new Request(110, null, null, null)).allocatedStorage());
        assertEquals(22, RdsInstanceStorage.forModify("postgres", new Storage(20, "gp3", 3000, 125),
                new Request(22, null, null, null)).allocatedStorage());
    }

    @Test
    void anEmptyRequestLeavesTheStorageAlone() {
        Storage current = new Storage(20, "gp2", null, null);
        assertSame(current, RdsInstanceStorage.forModify("postgres", current, new Request(null, null, null, null)));
    }

    @Test
    void queuedStorageChangesProjectOntoTheCurrentStorage() {
        DbInstancePendingModifiedValues pending = new DbInstancePendingModifiedValues();
        pending.setStorageType("gp2");
        assertEquals(new Storage(20, "gp2", null, null),
                RdsInstanceStorage.withPending(new Storage(20, "gp3", 3000, 125), pending));

        DbInstancePendingModifiedValues grow = new DbInstancePendingModifiedValues();
        grow.setAllocatedStorage(500);
        assertEquals(new Storage(500, "gp3", 12000, 500),
                RdsInstanceStorage.withPending(new Storage(400, "gp3", 12000, 500), grow));
    }
}
