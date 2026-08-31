package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SageMakerComputeQuota {

    private String computeQuotaId;
    private String computeQuotaArn;
    private String name;
    private String description;
    private int computeQuotaVersion;
    private String status;
    private String failureReason;
    private String clusterArn;
    private String activationState;
    private String region;
    private long creationTime;
    private long lastModifiedTime;
    private Map<String, Object> computeQuotaConfig;
    private Map<String, Object> computeQuotaTarget;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SageMakerComputeQuota() {
    }

    public String getComputeQuotaId() {
        return computeQuotaId;
    }

    public void setComputeQuotaId(String computeQuotaId) {
        this.computeQuotaId = computeQuotaId;
    }

    public String getComputeQuotaArn() {
        return computeQuotaArn;
    }

    public void setComputeQuotaArn(String computeQuotaArn) {
        this.computeQuotaArn = computeQuotaArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getComputeQuotaVersion() {
        return computeQuotaVersion;
    }

    public void setComputeQuotaVersion(int computeQuotaVersion) {
        this.computeQuotaVersion = computeQuotaVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getClusterArn() {
        return clusterArn;
    }

    public void setClusterArn(String clusterArn) {
        this.clusterArn = clusterArn;
    }

    public String getActivationState() {
        return activationState;
    }

    public void setActivationState(String activationState) {
        this.activationState = activationState;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public Map<String, Object> getComputeQuotaConfig() {
        return computeQuotaConfig;
    }

    public void setComputeQuotaConfig(Map<String, Object> computeQuotaConfig) {
        this.computeQuotaConfig = computeQuotaConfig;
    }

    public Map<String, Object> getComputeQuotaTarget() {
        return computeQuotaTarget;
    }

    public void setComputeQuotaTarget(Map<String, Object> computeQuotaTarget) {
        this.computeQuotaTarget = computeQuotaTarget;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
