package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class CacheSnapshot {

    private String snapshotName;
    private String replicationGroupId;

    public CacheSnapshot() {}

    public CacheSnapshot(String snapshotName, String replicationGroupId) {
        this.snapshotName = snapshotName;
        this.replicationGroupId = replicationGroupId;
    }

    public String getSnapshotName() { return snapshotName; }
    public void setSnapshotName(String snapshotName) { this.snapshotName = snapshotName; }

    public String getReplicationGroupId() { return replicationGroupId; }
    public void setReplicationGroupId(String replicationGroupId) { this.replicationGroupId = replicationGroupId; }
}
