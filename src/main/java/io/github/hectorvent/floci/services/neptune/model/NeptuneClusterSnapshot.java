package io.github.hectorvent.floci.services.neptune.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class NeptuneClusterSnapshot {

    private String dbClusterSnapshotIdentifier;
    private String dbClusterSnapshotArn;
    private String dbClusterIdentifier;
    private String status = "available";
    private String engine = "neptune";
    private String snapshotType = "manual";
    private Instant snapshotCreateTime = Instant.now();

    public NeptuneClusterSnapshot() {}

    public String getDbClusterSnapshotIdentifier() {
        return dbClusterSnapshotIdentifier;
    }

    public void setDbClusterSnapshotIdentifier(String dbClusterSnapshotIdentifier) {
        this.dbClusterSnapshotIdentifier = dbClusterSnapshotIdentifier;
    }

    public String getDbClusterSnapshotArn() {
        return dbClusterSnapshotArn;
    }

    public void setDbClusterSnapshotArn(String dbClusterSnapshotArn) {
        this.dbClusterSnapshotArn = dbClusterSnapshotArn;
    }

    public String getDbClusterIdentifier() {
        return dbClusterIdentifier;
    }

    public void setDbClusterIdentifier(String dbClusterIdentifier) {
        this.dbClusterIdentifier = dbClusterIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getSnapshotType() {
        return snapshotType;
    }

    public void setSnapshotType(String snapshotType) {
        this.snapshotType = snapshotType;
    }

    public Instant getSnapshotCreateTime() {
        return snapshotCreateTime;
    }

    public void setSnapshotCreateTime(Instant snapshotCreateTime) {
        this.snapshotCreateTime = snapshotCreateTime;
    }
}
