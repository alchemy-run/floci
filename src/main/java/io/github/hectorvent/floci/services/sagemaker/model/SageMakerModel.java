package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SageMakerModel {

    private String modelName;
    private String modelArn;
    private String region;
    private String executionRoleArn;
    private Map<String, Object> primaryContainer;
    private List<Map<String, Object>> containers = new ArrayList<>();
    private Map<String, Object> inferenceExecutionConfig;
    private Map<String, Object> vpcConfig;
    private boolean enableNetworkIsolation;
    private long creationTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SageMakerModel() {
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelArn() {
        return modelArn;
    }

    public void setModelArn(String modelArn) {
        this.modelArn = modelArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public Map<String, Object> getPrimaryContainer() {
        return primaryContainer;
    }

    public void setPrimaryContainer(Map<String, Object> primaryContainer) {
        this.primaryContainer = primaryContainer;
    }

    public List<Map<String, Object>> getContainers() {
        return containers;
    }

    public void setContainers(List<Map<String, Object>> containers) {
        this.containers = containers != null ? containers : new ArrayList<>();
    }

    public Map<String, Object> getInferenceExecutionConfig() {
        return inferenceExecutionConfig;
    }

    public void setInferenceExecutionConfig(Map<String, Object> inferenceExecutionConfig) {
        this.inferenceExecutionConfig = inferenceExecutionConfig;
    }

    public Map<String, Object> getVpcConfig() {
        return vpcConfig;
    }

    public void setVpcConfig(Map<String, Object> vpcConfig) {
        this.vpcConfig = vpcConfig;
    }

    public boolean isEnableNetworkIsolation() {
        return enableNetworkIsolation;
    }

    public void setEnableNetworkIsolation(boolean enableNetworkIsolation) {
        this.enableNetworkIsolation = enableNetworkIsolation;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
