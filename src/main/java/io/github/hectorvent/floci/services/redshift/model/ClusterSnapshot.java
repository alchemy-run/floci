package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class ClusterSnapshot {

    private String snapshotIdentifier;
    private String clusterIdentifier;
    private String snapshotType;
    private String status;
    private String nodeType;
    private int numberOfNodes;
    private int port;
    private String availabilityZone;
    private String masterUsername;
    private String dbName;
    private String clusterVersion;
    private boolean encrypted;
    private Instant snapshotCreateTime;
    private Instant clusterCreateTime;
    private String snapshotArn;
    private String ownerAccount;

    public ClusterSnapshot() {}

    public String getSnapshotIdentifier() { return snapshotIdentifier; }
    public void setSnapshotIdentifier(String snapshotIdentifier) { this.snapshotIdentifier = snapshotIdentifier; }

    public String getClusterIdentifier() { return clusterIdentifier; }
    public void setClusterIdentifier(String clusterIdentifier) { this.clusterIdentifier = clusterIdentifier; }

    public String getSnapshotType() { return snapshotType; }
    public void setSnapshotType(String snapshotType) { this.snapshotType = snapshotType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public int getNumberOfNodes() { return numberOfNodes; }
    public void setNumberOfNodes(int numberOfNodes) { this.numberOfNodes = numberOfNodes; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }

    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }

    public String getClusterVersion() { return clusterVersion; }
    public void setClusterVersion(String clusterVersion) { this.clusterVersion = clusterVersion; }

    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }

    public Instant getSnapshotCreateTime() { return snapshotCreateTime; }
    public void setSnapshotCreateTime(Instant snapshotCreateTime) { this.snapshotCreateTime = snapshotCreateTime; }

    public Instant getClusterCreateTime() { return clusterCreateTime; }
    public void setClusterCreateTime(Instant clusterCreateTime) { this.clusterCreateTime = clusterCreateTime; }

    public String getSnapshotArn() { return snapshotArn; }
    public void setSnapshotArn(String snapshotArn) { this.snapshotArn = snapshotArn; }

    public String getOwnerAccount() { return ownerAccount; }
    public void setOwnerAccount(String ownerAccount) { this.ownerAccount = ownerAccount; }
}
