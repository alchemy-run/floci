package io.github.hectorvent.floci.services.docdbelastic.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon DocumentDB elastic cluster snapshot. Wire names are camelCase. */
@RegisterForReflection
public class ClusterSnapshot {

    private String snapshotName;
    private String snapshotArn;
    private String snapshotId;
    private String clusterArn;
    private String clusterCreationTime;
    private String snapshotCreationTime;
    private String status;
    private String adminUserName;
    private String kmsKeyId;
    private String snapshotType;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private String region;

    public ClusterSnapshot() {
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public void setSnapshotName(String snapshotName) {
        this.snapshotName = snapshotName;
    }

    public String getSnapshotArn() {
        return snapshotArn;
    }

    public void setSnapshotArn(String snapshotArn) {
        this.snapshotArn = snapshotArn;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public String getClusterArn() {
        return clusterArn;
    }

    public void setClusterArn(String clusterArn) {
        this.clusterArn = clusterArn;
    }

    public String getClusterCreationTime() {
        return clusterCreationTime;
    }

    public void setClusterCreationTime(String clusterCreationTime) {
        this.clusterCreationTime = clusterCreationTime;
    }

    public String getSnapshotCreationTime() {
        return snapshotCreationTime;
    }

    public void setSnapshotCreationTime(String snapshotCreationTime) {
        this.snapshotCreationTime = snapshotCreationTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAdminUserName() {
        return adminUserName;
    }

    public void setAdminUserName(String adminUserName) {
        this.adminUserName = adminUserName;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getSnapshotType() {
        return snapshotType;
    }

    public void setSnapshotType(String snapshotType) {
        this.snapshotType = snapshotType;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds == null ? new ArrayList<>() : subnetIds;
    }

    public List<String> getVpcSecurityGroupIds() {
        return vpcSecurityGroupIds;
    }

    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds == null ? new ArrayList<>() : vpcSecurityGroupIds;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
