package io.github.hectorvent.floci.services.cloudhsmv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudHsmBackup {

    private String backupId;
    private String backupArn;
    private String backupState;
    private String clusterId;
    private String region;
    private long createTimestamp;
    private Long copyTimestamp;
    private boolean neverExpires;
    private String sourceRegion;
    private String sourceBackup;
    private String sourceCluster;
    private Long deleteTimestamp;
    private String hsmType;
    private String mode;
    private String resourcePolicy;
    private Map<String, String> tags = new LinkedHashMap<>();

    public CloudHsmBackup() {
    }

    public String getBackupId() {
        return backupId;
    }

    public void setBackupId(String backupId) {
        this.backupId = backupId;
    }

    public String getBackupArn() {
        return backupArn;
    }

    public void setBackupArn(String backupArn) {
        this.backupArn = backupArn;
    }

    public String getBackupState() {
        return backupState;
    }

    public void setBackupState(String backupState) {
        this.backupState = backupState;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getCreateTimestamp() {
        return createTimestamp;
    }

    public void setCreateTimestamp(long createTimestamp) {
        this.createTimestamp = createTimestamp;
    }

    public Long getCopyTimestamp() {
        return copyTimestamp;
    }

    public void setCopyTimestamp(Long copyTimestamp) {
        this.copyTimestamp = copyTimestamp;
    }

    public boolean isNeverExpires() {
        return neverExpires;
    }

    public void setNeverExpires(boolean neverExpires) {
        this.neverExpires = neverExpires;
    }

    public String getSourceRegion() {
        return sourceRegion;
    }

    public void setSourceRegion(String sourceRegion) {
        this.sourceRegion = sourceRegion;
    }

    public String getSourceBackup() {
        return sourceBackup;
    }

    public void setSourceBackup(String sourceBackup) {
        this.sourceBackup = sourceBackup;
    }

    public String getSourceCluster() {
        return sourceCluster;
    }

    public void setSourceCluster(String sourceCluster) {
        this.sourceCluster = sourceCluster;
    }

    public Long getDeleteTimestamp() {
        return deleteTimestamp;
    }

    public void setDeleteTimestamp(Long deleteTimestamp) {
        this.deleteTimestamp = deleteTimestamp;
    }

    public String getHsmType() {
        return hsmType;
    }

    public void setHsmType(String hsmType) {
        this.hsmType = hsmType;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getResourcePolicy() {
        return resourcePolicy;
    }

    public void setResourcePolicy(String resourcePolicy) {
        this.resourcePolicy = resourcePolicy;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
