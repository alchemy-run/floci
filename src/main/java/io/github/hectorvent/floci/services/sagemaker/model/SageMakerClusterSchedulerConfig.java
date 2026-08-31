package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SageMakerClusterSchedulerConfig {

    private String clusterSchedulerConfigId;
    private String clusterSchedulerConfigArn;
    private String name;
    private String clusterArn;
    private String region;
    private String status;
    private String description;
    private int clusterSchedulerConfigVersion;
    private long creationTime;
    private long lastModifiedTime;
    private Map<String, Object> schedulerConfig;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SageMakerClusterSchedulerConfig() {
    }

    public String getClusterSchedulerConfigId() {
        return clusterSchedulerConfigId;
    }

    public void setClusterSchedulerConfigId(String clusterSchedulerConfigId) {
        this.clusterSchedulerConfigId = clusterSchedulerConfigId;
    }

    public String getClusterSchedulerConfigArn() {
        return clusterSchedulerConfigArn;
    }

    public void setClusterSchedulerConfigArn(String clusterSchedulerConfigArn) {
        this.clusterSchedulerConfigArn = clusterSchedulerConfigArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getClusterArn() {
        return clusterArn;
    }

    public void setClusterArn(String clusterArn) {
        this.clusterArn = clusterArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getClusterSchedulerConfigVersion() {
        return clusterSchedulerConfigVersion;
    }

    public void setClusterSchedulerConfigVersion(int clusterSchedulerConfigVersion) {
        this.clusterSchedulerConfigVersion = clusterSchedulerConfigVersion;
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

    public Map<String, Object> getSchedulerConfig() {
        return schedulerConfig;
    }

    public void setSchedulerConfig(Map<String, Object> schedulerConfig) {
        this.schedulerConfig = schedulerConfig;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
