package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ElastiCache serverless cache record. Only the control-plane state is persisted; the backing
 * engine container is process-local and tracked by {@code ElastiCacheServerlessService}.
 */
@RegisterForReflection
public class ServerlessCache {

    private String serverlessCacheName;
    private String arn;
    private String accountId;
    private String region;
    private String description;
    private String status;
    private String engine;
    private String majorEngineVersion;
    private String fullEngineVersion;
    private Integer dataStorageMaximum;
    private Integer dataStorageMinimum;
    private String dataStorageUnit;
    private Integer ecpuPerSecondMaximum;
    private Integer ecpuPerSecondMinimum;
    private String kmsKeyId;
    private List<String> securityGroupIds = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private String vpcId;
    private Endpoint endpoint;
    private Endpoint readerEndpoint;
    private String userGroupId;
    private int snapshotRetentionLimit;
    private String dailySnapshotTime;
    private String networkType;
    private Instant createTime;
    private List<String> snapshotNamesToRestore = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ServerlessCache() {
    }

    public ServerlessCache copy() {
        ServerlessCache copy = new ServerlessCache();
        copy.serverlessCacheName = serverlessCacheName;
        copy.arn = arn;
        copy.accountId = accountId;
        copy.region = region;
        copy.description = description;
        copy.status = status;
        copy.engine = engine;
        copy.majorEngineVersion = majorEngineVersion;
        copy.fullEngineVersion = fullEngineVersion;
        copy.dataStorageMaximum = dataStorageMaximum;
        copy.dataStorageMinimum = dataStorageMinimum;
        copy.dataStorageUnit = dataStorageUnit;
        copy.ecpuPerSecondMaximum = ecpuPerSecondMaximum;
        copy.ecpuPerSecondMinimum = ecpuPerSecondMinimum;
        copy.kmsKeyId = kmsKeyId;
        copy.securityGroupIds = new ArrayList<>(securityGroupIds);
        copy.subnetIds = new ArrayList<>(subnetIds);
        copy.vpcId = vpcId;
        copy.endpoint = endpoint;
        copy.readerEndpoint = readerEndpoint;
        copy.userGroupId = userGroupId;
        copy.snapshotRetentionLimit = snapshotRetentionLimit;
        copy.dailySnapshotTime = dailySnapshotTime;
        copy.networkType = networkType;
        copy.createTime = createTime;
        copy.snapshotNamesToRestore = new ArrayList<>(snapshotNamesToRestore);
        copy.tags = new LinkedHashMap<>(tags);
        return copy;
    }

    public String getServerlessCacheName() { return serverlessCacheName; }
    public void setServerlessCacheName(String serverlessCacheName) { this.serverlessCacheName = serverlessCacheName; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public String getMajorEngineVersion() { return majorEngineVersion; }
    public void setMajorEngineVersion(String majorEngineVersion) { this.majorEngineVersion = majorEngineVersion; }

    public String getFullEngineVersion() { return fullEngineVersion; }
    public void setFullEngineVersion(String fullEngineVersion) { this.fullEngineVersion = fullEngineVersion; }

    public Integer getDataStorageMaximum() { return dataStorageMaximum; }
    public void setDataStorageMaximum(Integer dataStorageMaximum) { this.dataStorageMaximum = dataStorageMaximum; }

    public Integer getDataStorageMinimum() { return dataStorageMinimum; }
    public void setDataStorageMinimum(Integer dataStorageMinimum) { this.dataStorageMinimum = dataStorageMinimum; }

    public String getDataStorageUnit() { return dataStorageUnit; }
    public void setDataStorageUnit(String dataStorageUnit) { this.dataStorageUnit = dataStorageUnit; }

    public Integer getEcpuPerSecondMaximum() { return ecpuPerSecondMaximum; }
    public void setEcpuPerSecondMaximum(Integer ecpuPerSecondMaximum) { this.ecpuPerSecondMaximum = ecpuPerSecondMaximum; }

    public Integer getEcpuPerSecondMinimum() { return ecpuPerSecondMinimum; }
    public void setEcpuPerSecondMinimum(Integer ecpuPerSecondMinimum) { this.ecpuPerSecondMinimum = ecpuPerSecondMinimum; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>();
    }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public Endpoint getEndpoint() { return endpoint; }
    public void setEndpoint(Endpoint endpoint) { this.endpoint = endpoint; }

    public Endpoint getReaderEndpoint() { return readerEndpoint; }
    public void setReaderEndpoint(Endpoint readerEndpoint) { this.readerEndpoint = readerEndpoint; }

    public String getUserGroupId() { return userGroupId; }
    public void setUserGroupId(String userGroupId) { this.userGroupId = userGroupId; }

    public int getSnapshotRetentionLimit() { return snapshotRetentionLimit; }
    public void setSnapshotRetentionLimit(int snapshotRetentionLimit) { this.snapshotRetentionLimit = snapshotRetentionLimit; }

    public String getDailySnapshotTime() { return dailySnapshotTime; }
    public void setDailySnapshotTime(String dailySnapshotTime) { this.dailySnapshotTime = dailySnapshotTime; }

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public List<String> getSnapshotNamesToRestore() { return snapshotNamesToRestore; }
    public void setSnapshotNamesToRestore(List<String> snapshotNamesToRestore) {
        this.snapshotNamesToRestore = snapshotNamesToRestore != null
                ? new ArrayList<>(snapshotNamesToRestore) : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
