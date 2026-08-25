package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerServiceRecord {

    private String serviceName;
    private String serviceId;
    private String serviceArn;
    private String serviceUrl;
    private String region;
    private String status;
    private long createdAt;
    private long updatedAt;
    private JsonNode sourceConfiguration;
    private JsonNode instanceConfiguration;
    private JsonNode healthCheckConfiguration;
    private JsonNode networkConfiguration;
    private JsonNode observabilityConfiguration;
    private JsonNode encryptionConfiguration;
    private String autoScalingConfigurationArn;
    private String autoScalingConfigurationName;
    private Integer autoScalingConfigurationRevision;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<AppRunnerOperation> operations = new ArrayList<>();

    public AppRunnerServiceRecord() {
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getServiceArn() {
        return serviceArn;
    }

    public void setServiceArn(String serviceArn) {
        this.serviceArn = serviceArn;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
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

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public JsonNode getSourceConfiguration() {
        return sourceConfiguration;
    }

    public void setSourceConfiguration(JsonNode sourceConfiguration) {
        this.sourceConfiguration = sourceConfiguration;
    }

    public JsonNode getInstanceConfiguration() {
        return instanceConfiguration;
    }

    public void setInstanceConfiguration(JsonNode instanceConfiguration) {
        this.instanceConfiguration = instanceConfiguration;
    }

    public JsonNode getHealthCheckConfiguration() {
        return healthCheckConfiguration;
    }

    public void setHealthCheckConfiguration(JsonNode healthCheckConfiguration) {
        this.healthCheckConfiguration = healthCheckConfiguration;
    }

    public JsonNode getNetworkConfiguration() {
        return networkConfiguration;
    }

    public void setNetworkConfiguration(JsonNode networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public JsonNode getObservabilityConfiguration() {
        return observabilityConfiguration;
    }

    public void setObservabilityConfiguration(JsonNode observabilityConfiguration) {
        this.observabilityConfiguration = observabilityConfiguration;
    }

    public JsonNode getEncryptionConfiguration() {
        return encryptionConfiguration;
    }

    public void setEncryptionConfiguration(JsonNode encryptionConfiguration) {
        this.encryptionConfiguration = encryptionConfiguration;
    }

    public String getAutoScalingConfigurationArn() {
        return autoScalingConfigurationArn;
    }

    public void setAutoScalingConfigurationArn(String autoScalingConfigurationArn) {
        this.autoScalingConfigurationArn = autoScalingConfigurationArn;
    }

    public String getAutoScalingConfigurationName() {
        return autoScalingConfigurationName;
    }

    public void setAutoScalingConfigurationName(String autoScalingConfigurationName) {
        this.autoScalingConfigurationName = autoScalingConfigurationName;
    }

    public Integer getAutoScalingConfigurationRevision() {
        return autoScalingConfigurationRevision;
    }

    public void setAutoScalingConfigurationRevision(Integer autoScalingConfigurationRevision) {
        this.autoScalingConfigurationRevision = autoScalingConfigurationRevision;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public List<AppRunnerOperation> getOperations() {
        return operations;
    }

    public void setOperations(List<AppRunnerOperation> operations) {
        this.operations = operations != null ? operations : new ArrayList<>();
    }
}
