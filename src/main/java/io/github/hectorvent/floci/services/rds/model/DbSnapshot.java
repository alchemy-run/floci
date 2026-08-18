package io.github.hectorvent.floci.services.rds.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class DbSnapshot {

    private String dbSnapshotIdentifier;
    private String dbSnapshotArn;
    private String dbInstanceIdentifier;
    private String status = "available";
    private String engine;
    private String snapshotType = "manual";
    private Instant snapshotCreateTime = Instant.now();
    private Map<String, String> tags = new LinkedHashMap<>();

    public DbSnapshot() {}

    public String getDbSnapshotIdentifier() { return dbSnapshotIdentifier; }
    public void setDbSnapshotIdentifier(String dbSnapshotIdentifier) {
        this.dbSnapshotIdentifier = dbSnapshotIdentifier;
    }

    public String getDbSnapshotArn() { return dbSnapshotArn; }
    public void setDbSnapshotArn(String dbSnapshotArn) { this.dbSnapshotArn = dbSnapshotArn; }

    public String getDbInstanceIdentifier() { return dbInstanceIdentifier; }
    public void setDbInstanceIdentifier(String dbInstanceIdentifier) {
        this.dbInstanceIdentifier = dbInstanceIdentifier;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }

    public Instant getSnapshotCreateTime() { return snapshotCreateTime; }
    public void setSnapshotCreateTime(Instant snapshotCreateTime) {
        this.snapshotCreateTime = snapshotCreateTime;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
