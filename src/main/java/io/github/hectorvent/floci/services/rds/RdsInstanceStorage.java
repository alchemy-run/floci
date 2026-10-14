package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstancePendingModifiedValues;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The storage of a standalone DB instance as RDS resolves it from a CreateDBInstance or
 * ModifyDBInstance request: the storage type, the allocation, and the provisioned IOPS and
 * throughput with the baseline each storage type implies when the request leaves them out.
 *
 * <p>gp3 volumes below the striping threshold (400 GiB, or 200 GiB for Oracle) have a fixed
 * baseline of 3000 IOPS and 125 MiBps that a request cannot name. At or above it the baseline is
 * 12000 IOPS and 500 MiBps and both can be provisioned higher, with throughput at most a quarter
 * of the IOPS. SQL Server gp3 is not striped and can provision performance at any size. io1 and
 * io2 carry provisioned IOPS and no throughput; gp2 and magnetic storage carry neither.
 */
final class RdsInstanceStorage {

    static final String STANDARD = "standard";
    static final String GP2 = "gp2";
    static final String GP3 = "gp3";
    static final String IO1 = "io1";
    static final String IO2 = "io2";
    static final String AURORA = "aurora";

    static final int GP3_BASELINE_IOPS = 3000;
    static final int GP3_BASELINE_THROUGHPUT = 125;
    static final int GP3_STRIPED_IOPS = 12000;
    static final int GP3_STRIPED_THROUGHPUT = 500;

    private static final Set<String> STORAGE_TYPES = Set.of(STANDARD, GP2, GP3, IO1, IO2);

    private RdsInstanceStorage() {}

    /** The four storage members DescribeDBInstances reports; {@code iops} and throughput may be null. */
    record Storage(int allocatedStorage, String storageType, Integer iops, Integer storageThroughput) {}

    /** The storage members a request carries; a null member is one it left out. */
    record Request(Integer allocatedStorage, String storageType, Integer iops, Integer storageThroughput) {

        boolean isEmpty() {
            return allocatedStorage == null && storageType == null && iops == null && storageThroughput == null;
        }
    }

    static boolean isAurora(String engine) {
        return engine != null && engine.toLowerCase(Locale.ROOT).startsWith("aurora");
    }

    /**
     * The storage an instance reports. A record persisted before storage was modelled reads the
     * way Floci used to report it, gp2; an Aurora member reports the cluster volume.
     */
    static Storage current(DbInstance instance, String engine) {
        String type = instance.getStorageType();
        if (type == null) {
            type = isAurora(engine) ? AURORA : GP2;
        }
        return new Storage(instance.getAllocatedStorage(), type, instance.getIops(), instance.getStorageThroughput());
    }

    /**
     * The storage once the queued storage changes are applied. PendingModifiedValues names only
     * the members that change, so a member left out keeps its value where the new storage type
     * still has it: IOPS on io1, io2 and gp3, throughput on gp3.
     */
    static Storage withPending(Storage current, DbInstancePendingModifiedValues pending) {
        if (pending == null || !pending.hasStorageChanges()) {
            return current;
        }
        String type = pending.getStorageType() != null ? pending.getStorageType() : current.storageType();
        int allocation = pending.getAllocatedStorage() != null
                ? pending.getAllocatedStorage() : current.allocatedStorage();
        boolean provisionsIops = GP3.equals(type) || IO1.equals(type) || IO2.equals(type);
        Integer iops = pending.getIops() != null ? pending.getIops() : provisionsIops ? current.iops() : null;
        Integer throughput = pending.getStorageThroughput() != null ? pending.getStorageThroughput()
                : GP3.equals(type) ? current.storageThroughput() : null;
        return new Storage(allocation, type, iops, throughput);
    }

    /**
     * CreateDBInstance's storage. StorageType defaults to io1 when Iops is given and to gp3
     * otherwise. An Aurora instance stores its data in the cluster volume and reports it as
     * aurora whatever the request says.
     */
    static Storage forCreate(String engine, int allocatedStorage, String storageType, Integer iops,
                             Integer storageThroughput) {
        if (isAurora(engine)) {
            return new Storage(allocatedStorage, AURORA, null, null);
        }
        String type = storageType != null && !storageType.isBlank()
                ? storageType.toLowerCase(Locale.ROOT)
                : iops != null ? IO1 : GP3;
        return resolve(engine, null, allocatedStorage, type, iops, storageThroughput);
    }

    /**
     * The storage a ModifyDBInstance request leads to from {@code current}. Members the request
     * leaves out keep their current value, except where a storage type or striping change moves
     * the baseline.
     */
    static Storage forModify(String engine, Storage current, Request request) {
        if (request.isEmpty() || AURORA.equals(current.storageType())) {
            return current;
        }
        String type = request.storageType() != null && !request.storageType().isBlank()
                ? request.storageType().toLowerCase(Locale.ROOT)
                : current.storageType();
        int allocation = request.allocatedStorage() != null
                ? request.allocatedStorage() : current.allocatedStorage();
        if (allocation < current.allocatedStorage()) {
            throw new AwsException("InvalidParameterCombination",
                    "Invalid storage size for engine name " + engineName(engine) + " and storage type "
                            + type + ": " + allocation + ". The allocated storage cannot be decreased from "
                            + current.allocatedStorage() + " GiB.", 400);
        }
        int minimumIncrease = (current.allocatedStorage() * 11 + 9) / 10;
        if (allocation > current.allocatedStorage() && allocation < minimumIncrease) {
            throw new AwsException("InvalidParameterCombination",
                    "The allocated storage must be increased by at least 10 percent.", 400);
        }
        return resolve(engine, current, allocation, type, request.iops(), request.storageThroughput());
    }

    static boolean differs(Storage a, Storage b) {
        return a.allocatedStorage() != b.allocatedStorage()
                || !Objects.equals(a.storageType(), b.storageType())
                || !Objects.equals(a.iops(), b.iops())
                || !Objects.equals(a.storageThroughput(), b.storageThroughput());
    }

    private static Storage resolve(String engine, Storage current, int allocation, String type,
                                   Integer iops, Integer throughput) {
        if (!STORAGE_TYPES.contains(type)) {
            throw new AwsException("InvalidParameterValue", "Invalid storage type: " + type
                    + ". Valid values are standard, gp2, gp3, io1 and io2.", 400);
        }
        return switch (type) {
            case GP3 -> resolveGp3(engine, current, allocation, iops, throughput);
            case IO1, IO2 -> resolveProvisioned(current, allocation, type, iops, throughput);
            default -> {
                if (iops != null) {
                    throw new AwsException("InvalidParameterCombination",
                            "You can specify IOPS only for the io1, io2 and gp3 storage types.", 400);
                }
                if (throughput != null) {
                    throw storageThroughputRequiresGp3();
                }
                yield new Storage(allocation, type, null, null);
            }
        };
    }

    private static Storage resolveGp3(String engine, Storage current, int allocation, Integer iops,
                                      Integer throughput) {
        boolean configurable = gp3Configurable(engine, allocation);
        if (!configurable) {
            if (iops != null || throughput != null) {
                throw new AwsException("InvalidParameterCombination",
                        "You can't specify IOPS or storage throughput for engine " + engineName(engine)
                                + " and a storage size less than " + stripingThreshold(engine) + ".", 400);
            }
            return new Storage(allocation, GP3, GP3_BASELINE_IOPS, GP3_BASELINE_THROUGHPUT);
        }
        boolean striped = gp3Striped(engine, allocation);
        int baselineIops = striped ? GP3_STRIPED_IOPS : GP3_BASELINE_IOPS;
        int baselineThroughput = striped ? GP3_STRIPED_THROUGHPUT : GP3_BASELINE_THROUGHPUT;
        // Provisioned gp3 performance survives a resize; otherwise the new baseline applies.
        boolean keepCurrent = current != null && GP3.equals(current.storageType())
                && gp3Configurable(engine, current.allocatedStorage());
        int effectiveIops = iops != null ? iops
                : keepCurrent && current.iops() != null ? Math.max(current.iops(), baselineIops) : baselineIops;
        int effectiveThroughput = throughput != null ? throughput
                : keepCurrent && current.storageThroughput() != null
                ? Math.max(current.storageThroughput(), baselineThroughput) : baselineThroughput;
        if (effectiveIops < baselineIops) {
            throw new AwsException("InvalidParameterCombination", "Invalid IOPS " + effectiveIops
                    + " for gp3 storage of " + allocation + " GiB. The minimum is " + baselineIops + ".", 400);
        }
        if (effectiveThroughput < baselineThroughput) {
            throw new AwsException("InvalidParameterCombination", "Invalid storage throughput "
                    + effectiveThroughput + " for gp3 storage of " + allocation + " GiB. The minimum is "
                    + baselineThroughput + " MiBps.", 400);
        }
        if (effectiveThroughput * 4L > effectiveIops) {
            throw new AwsException("InvalidParameterCombination",
                    "The storage throughput to IOPS ratio can't be more than 0.25.", 400);
        }
        return new Storage(allocation, GP3, effectiveIops, effectiveThroughput);
    }

    private static Storage resolveProvisioned(Storage current, int allocation, String type, Integer iops,
                                              Integer throughput) {
        if (throughput != null) {
            throw storageThroughputRequiresGp3();
        }
        Integer effectiveIops = iops;
        if (effectiveIops == null && current != null
                && (IO1.equals(current.storageType()) || IO2.equals(current.storageType()))) {
            effectiveIops = current.iops();
        }
        if (effectiveIops == null) {
            throw new AwsException("InvalidParameterCombination",
                    "Provisioned IOPS storage (" + type + ") requires a value for Iops.", 400);
        }
        return new Storage(allocation, type, effectiveIops, null);
    }

    private static AwsException storageThroughputRequiresGp3() {
        return new AwsException("InvalidParameterCombination",
                "You can specify storage throughput only for the gp3 storage type.", 400);
    }

    private static boolean gp3Configurable(String engine, int allocation) {
        return isSqlServer(engine) || gp3Striped(engine, allocation);
    }

    private static boolean gp3Striped(String engine, int allocation) {
        return !isSqlServer(engine) && allocation >= stripingThreshold(engine);
    }

    private static int stripingThreshold(String engine) {
        return engineName(engine).startsWith("oracle") ? 200 : 400;
    }

    private static boolean isSqlServer(String engine) {
        return engineName(engine).startsWith("sqlserver");
    }

    private static String engineName(String engine) {
        return engine == null || engine.isBlank() ? "postgres" : engine.toLowerCase(Locale.ROOT);
    }
}
