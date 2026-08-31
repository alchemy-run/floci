package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ServerlessCache {

    private String serverlessCacheName;
    private String description;
    private Instant createTime;
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
    private String storageEncryptionType;
    private List<String> securityGroupIds = new ArrayList<>();
    private Endpoint endpoint;
    private Endpoint readerEndpoint;
    private String arn;
    private String userGroupId;
    private List<String> subnetIds = new ArrayList<>();
    private Integer snapshotRetentionLimit;
    private String dailySnapshotTime;
    private String networkType;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ServerlessCache() {}

    public String getServerlessCacheName() { return serverlessCacheName; }
    public void setServerlessCacheName(String serverlessCacheName) { this.serverlessCacheName = serverlessCacheName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

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

    public Integer getEcpuMaximum() { return ecpuPerSecondMaximum; }
    public void setEcpuMaximum(Integer ecpuMaximum) { this.ecpuPerSecondMaximum = ecpuMaximum; }

    public Integer getEcpuPerSecondMinimum() { return ecpuPerSecondMinimum; }
    public void setEcpuPerSecondMinimum(Integer ecpuPerSecondMinimum) { this.ecpuPerSecondMinimum = ecpuPerSecondMinimum; }

    public Integer getEcpuMinimum() { return ecpuPerSecondMinimum; }
    public void setEcpuMinimum(Integer ecpuMinimum) { this.ecpuPerSecondMinimum = ecpuMinimum; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public String getStorageEncryptionType() { return storageEncryptionType; }
    public void setStorageEncryptionType(String storageEncryptionType) { this.storageEncryptionType = storageEncryptionType; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? new ArrayList<>(securityGroupIds) : new ArrayList<>();
    }

    public Endpoint getEndpoint() { return endpoint; }
    public void setEndpoint(Endpoint endpoint) { this.endpoint = endpoint; }

    public Endpoint getReaderEndpoint() { return readerEndpoint; }
    public void setReaderEndpoint(Endpoint readerEndpoint) { this.readerEndpoint = readerEndpoint; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getUserGroupId() { return userGroupId; }
    public void setUserGroupId(String userGroupId) { this.userGroupId = userGroupId; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? new ArrayList<>(subnetIds) : new ArrayList<>();
    }

    public Integer getSnapshotRetentionLimit() { return snapshotRetentionLimit; }
    public void setSnapshotRetentionLimit(Integer snapshotRetentionLimit) {
        this.snapshotRetentionLimit = snapshotRetentionLimit;
    }

    public String getDailySnapshotTime() { return dailySnapshotTime; }
    public void setDailySnapshotTime(String dailySnapshotTime) { this.dailySnapshotTime = dailySnapshotTime; }

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
