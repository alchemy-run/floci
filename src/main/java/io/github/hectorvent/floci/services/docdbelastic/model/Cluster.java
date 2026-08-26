package io.github.hectorvent.floci.services.docdbelastic.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon DocumentDB elastic cluster. Wire names are camelCase. */
@RegisterForReflection
public class Cluster {

    private String clusterName;
    private String clusterArn;
    private String clusterId;
    private String status;
    private String clusterEndpoint;
    private String createTime;
    private String adminUserName;
    private String adminUserPassword;
    private String authType;
    private int shardCapacity;
    private int shardCount;
    private Integer shardInstanceCount;
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private String preferredMaintenanceWindow;
    private String kmsKeyId;
    private Integer backupRetentionPeriod;
    private String preferredBackupWindow;
    private List<Shard> shards = new ArrayList<>();
    private List<PendingMaintenanceAction> pendingActions = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private String region;

    public Cluster() {
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getClusterArn() {
        return clusterArn;
    }

    public void setClusterArn(String clusterArn) {
        this.clusterArn = clusterArn;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getClusterEndpoint() {
        return clusterEndpoint;
    }

    public void setClusterEndpoint(String clusterEndpoint) {
        this.clusterEndpoint = clusterEndpoint;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getAdminUserName() {
        return adminUserName;
    }

    public void setAdminUserName(String adminUserName) {
        this.adminUserName = adminUserName;
    }

    public String getAdminUserPassword() {
        return adminUserPassword;
    }

    public void setAdminUserPassword(String adminUserPassword) {
        this.adminUserPassword = adminUserPassword;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public int getShardCapacity() {
        return shardCapacity;
    }

    public void setShardCapacity(int shardCapacity) {
        this.shardCapacity = shardCapacity;
    }

    public int getShardCount() {
        return shardCount;
    }

    public void setShardCount(int shardCount) {
        this.shardCount = shardCount;
    }

    public Integer getShardInstanceCount() {
        return shardInstanceCount;
    }

    public void setShardInstanceCount(Integer shardInstanceCount) {
        this.shardInstanceCount = shardInstanceCount;
    }

    public List<String> getVpcSecurityGroupIds() {
        return vpcSecurityGroupIds;
    }

    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds == null ? new ArrayList<>() : vpcSecurityGroupIds;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds == null ? new ArrayList<>() : subnetIds;
    }

    public String getPreferredMaintenanceWindow() {
        return preferredMaintenanceWindow;
    }

    public void setPreferredMaintenanceWindow(String preferredMaintenanceWindow) {
        this.preferredMaintenanceWindow = preferredMaintenanceWindow;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public Integer getBackupRetentionPeriod() {
        return backupRetentionPeriod;
    }

    public void setBackupRetentionPeriod(Integer backupRetentionPeriod) {
        this.backupRetentionPeriod = backupRetentionPeriod;
    }

    public String getPreferredBackupWindow() {
        return preferredBackupWindow;
    }

    public void setPreferredBackupWindow(String preferredBackupWindow) {
        this.preferredBackupWindow = preferredBackupWindow;
    }

    public List<Shard> getShards() {
        return shards;
    }

    public void setShards(List<Shard> shards) {
        this.shards = shards == null ? new ArrayList<>() : shards;
    }

    public List<PendingMaintenanceAction> getPendingActions() {
        return pendingActions;
    }

    public void setPendingActions(List<PendingMaintenanceAction> pendingActions) {
        this.pendingActions = pendingActions == null ? new ArrayList<>() : pendingActions;
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

    @RegisterForReflection
    public static class Shard {
        private String shardId;
        private String createTime;
        private String status;

        public Shard() {
        }

        public Shard(String shardId, String createTime, String status) {
            this.shardId = shardId;
            this.createTime = createTime;
            this.status = status;
        }

        public String getShardId() {
            return shardId;
        }

        public void setShardId(String shardId) {
            this.shardId = shardId;
        }

        public String getCreateTime() {
            return createTime;
        }

        public void setCreateTime(String createTime) {
            this.createTime = createTime;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    @RegisterForReflection
    public static class PendingMaintenanceAction {
        private String action;
        private String optInStatus;
        private String currentApplyDate;
        private String description;

        public PendingMaintenanceAction() {
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getOptInStatus() {
            return optInStatus;
        }

        public void setOptInStatus(String optInStatus) {
            this.optInStatus = optInStatus;
        }

        public String getCurrentApplyDate() {
            return currentApplyDate;
        }

        public void setCurrentApplyDate(String currentApplyDate) {
            this.currentApplyDate = currentApplyDate;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
