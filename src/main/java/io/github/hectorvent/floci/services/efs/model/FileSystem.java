package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon EFS file system. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class FileSystem {

    private String fileSystemId;
    private String fileSystemArn;
    private String ownerId;
    private String creationToken;
    private long creationTime;
    private String lifeCycleState;
    private String performanceMode;
    private String throughputMode;
    private Double provisionedThroughputInMibps;
    private Boolean encrypted;
    private String kmsKeyId;
    private String availabilityZoneName;
    private String availabilityZoneId;
    private String replicationOverwriteProtection = "ENABLED";
    private String backupPolicyStatus;
    private String policy;
    private String region;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<Map<String, String>> lifecyclePolicies = new ArrayList<>();

    public FileSystem() {
    }

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }

    public String getFileSystemArn() {
        return fileSystemArn;
    }

    public void setFileSystemArn(String fileSystemArn) {
        this.fileSystemArn = fileSystemArn;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getCreationToken() {
        return creationToken;
    }

    public void setCreationToken(String creationToken) {
        this.creationToken = creationToken;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public String getLifeCycleState() {
        return lifeCycleState;
    }

    public void setLifeCycleState(String lifeCycleState) {
        this.lifeCycleState = lifeCycleState;
    }

    public String getPerformanceMode() {
        return performanceMode;
    }

    public void setPerformanceMode(String performanceMode) {
        this.performanceMode = performanceMode;
    }

    public String getThroughputMode() {
        return throughputMode;
    }

    public void setThroughputMode(String throughputMode) {
        this.throughputMode = throughputMode;
    }

    public Double getProvisionedThroughputInMibps() {
        return provisionedThroughputInMibps;
    }

    public void setProvisionedThroughputInMibps(Double provisionedThroughputInMibps) {
        this.provisionedThroughputInMibps = provisionedThroughputInMibps;
    }

    public Boolean getEncrypted() {
        return encrypted;
    }

    public void setEncrypted(Boolean encrypted) {
        this.encrypted = encrypted;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getAvailabilityZoneName() {
        return availabilityZoneName;
    }

    public void setAvailabilityZoneName(String availabilityZoneName) {
        this.availabilityZoneName = availabilityZoneName;
    }

    public String getAvailabilityZoneId() {
        return availabilityZoneId;
    }

    public void setAvailabilityZoneId(String availabilityZoneId) {
        this.availabilityZoneId = availabilityZoneId;
    }

    public String getReplicationOverwriteProtection() {
        return replicationOverwriteProtection;
    }

    public void setReplicationOverwriteProtection(String replicationOverwriteProtection) {
        this.replicationOverwriteProtection = replicationOverwriteProtection;
    }

    public String getBackupPolicyStatus() {
        return backupPolicyStatus;
    }

    public void setBackupPolicyStatus(String backupPolicyStatus) {
        this.backupPolicyStatus = backupPolicyStatus;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public List<Map<String, String>> getLifecyclePolicies() {
        return lifecyclePolicies;
    }

    public void setLifecyclePolicies(List<Map<String, String>> lifecyclePolicies) {
        this.lifecyclePolicies = lifecyclePolicies != null ? lifecyclePolicies : new ArrayList<>();
    }
}
