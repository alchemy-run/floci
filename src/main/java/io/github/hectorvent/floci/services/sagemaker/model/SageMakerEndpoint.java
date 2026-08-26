package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SageMakerEndpoint {

    private String endpointName;
    private String endpointArn;
    private String region;
    private String endpointConfigName;
    private String endpointStatus;
    private String failureReason;
    private List<Map<String, Object>> productionVariants = new ArrayList<>();
    private Map<String, Object> deploymentConfig;
    private long creationTime;
    private long lastModifiedTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SageMakerEndpoint() {
    }

    public String getEndpointName() {
        return endpointName;
    }

    public void setEndpointName(String endpointName) {
        this.endpointName = endpointName;
    }

    public String getEndpointArn() {
        return endpointArn;
    }

    public void setEndpointArn(String endpointArn) {
        this.endpointArn = endpointArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpointConfigName() {
        return endpointConfigName;
    }

    public void setEndpointConfigName(String endpointConfigName) {
        this.endpointConfigName = endpointConfigName;
    }

    public String getEndpointStatus() {
        return endpointStatus;
    }

    public void setEndpointStatus(String endpointStatus) {
        this.endpointStatus = endpointStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public List<Map<String, Object>> getProductionVariants() {
        return productionVariants;
    }

    public void setProductionVariants(List<Map<String, Object>> productionVariants) {
        this.productionVariants = productionVariants != null ? productionVariants : new ArrayList<>();
    }

    public Map<String, Object> getDeploymentConfig() {
        return deploymentConfig;
    }

    public void setDeploymentConfig(Map<String, Object> deploymentConfig) {
        this.deploymentConfig = deploymentConfig;
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

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
