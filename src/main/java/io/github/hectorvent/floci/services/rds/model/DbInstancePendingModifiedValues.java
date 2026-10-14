package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * The changes a ModifyDBInstance request queued with ApplyImmediately false, reported as
 * DescribeDBInstances PendingModifiedValues. RDS applies them during the next maintenance window,
 * or at once when a later ModifyDBInstance sets ApplyImmediately. A null member is not pending.
 */
@RegisterForReflection
public class DbInstancePendingModifiedValues {

    private String dbInstanceClass;
    private Integer allocatedStorage;
    private String storageType;
    private Integer iops;
    private Integer storageThroughput;
    private Integer backupRetentionPeriod;
    private Boolean multiAz;
    private Boolean iamDatabaseAuthenticationEnabled;
    /** When the oldest queued change was accepted; the next maintenance window after it applies the queue. */
    private Instant queuedAt;

    public DbInstancePendingModifiedValues() {}

    public boolean hasChanges() {
        return dbInstanceClass != null || allocatedStorage != null || storageType != null
                || iops != null || storageThroughput != null || backupRetentionPeriod != null
                || multiAz != null || iamDatabaseAuthenticationEnabled != null;
    }

    public boolean hasStorageChanges() {
        return allocatedStorage != null || storageType != null || iops != null || storageThroughput != null;
    }

    public void clearStorageChanges() {
        allocatedStorage = null;
        storageType = null;
        iops = null;
        storageThroughput = null;
    }

    public String getDbInstanceClass() { return dbInstanceClass; }
    public void setDbInstanceClass(String dbInstanceClass) { this.dbInstanceClass = dbInstanceClass; }

    public Integer getAllocatedStorage() { return allocatedStorage; }
    public void setAllocatedStorage(Integer allocatedStorage) { this.allocatedStorage = allocatedStorage; }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public Integer getIops() { return iops; }
    public void setIops(Integer iops) { this.iops = iops; }

    public Integer getStorageThroughput() { return storageThroughput; }
    public void setStorageThroughput(Integer storageThroughput) { this.storageThroughput = storageThroughput; }

    public Integer getBackupRetentionPeriod() { return backupRetentionPeriod; }
    public void setBackupRetentionPeriod(Integer backupRetentionPeriod) {
        this.backupRetentionPeriod = backupRetentionPeriod;
    }

    public Boolean getMultiAz() { return multiAz; }
    public void setMultiAz(Boolean multiAz) { this.multiAz = multiAz; }

    public Boolean getIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(Boolean iamDatabaseAuthenticationEnabled) {
        this.iamDatabaseAuthenticationEnabled = iamDatabaseAuthenticationEnabled;
    }

    public Instant getQueuedAt() { return queuedAt; }
    public void setQueuedAt(Instant queuedAt) { this.queuedAt = queuedAt; }
}
