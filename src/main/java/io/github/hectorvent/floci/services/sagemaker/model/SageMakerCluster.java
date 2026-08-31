package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SageMakerCluster {

    private String clusterName;
    private String clusterArn;
    private String clusterStatus;
    private String region;
    private String nodeRecovery;
    private String nodeProvisioningMode;
    private String clusterRole;
    private String failureMessage;
    private long creationTime;
    private List<Map<String, Object>> instanceGroups = new ArrayList<>();
    private List<Map<String, Object>> restrictedInstanceGroups = new ArrayList<>();
    private Map<String, Object> restrictedInstanceGroupsConfig;
    private Map<String, Object> vpcConfig;
    private Map<String, Object> orchestrator;
    private Map<String, Object> tieredStorageConfig;
    private Map<String, Object> autoScaling;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SageMakerCluster() {
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

    public String getClusterStatus() {
        return clusterStatus;
    }

    public void setClusterStatus(String clusterStatus) {
        this.clusterStatus = clusterStatus;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getNodeRecovery() {
        return nodeRecovery;
    }

    public void setNodeRecovery(String nodeRecovery) {
        this.nodeRecovery = nodeRecovery;
    }

    public String getNodeProvisioningMode() {
        return nodeProvisioningMode;
    }

    public void setNodeProvisioningMode(String nodeProvisioningMode) {
        this.nodeProvisioningMode = nodeProvisioningMode;
    }

    public String getClusterRole() {
        return clusterRole;
    }

    public void setClusterRole(String clusterRole) {
        this.clusterRole = clusterRole;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public List<Map<String, Object>> getInstanceGroups() {
        return instanceGroups;
    }

    public void setInstanceGroups(List<Map<String, Object>> instanceGroups) {
        this.instanceGroups = instanceGroups != null ? instanceGroups : new ArrayList<>();
    }

    public List<Map<String, Object>> getRestrictedInstanceGroups() {
        return restrictedInstanceGroups;
    }

    public void setRestrictedInstanceGroups(List<Map<String, Object>> restrictedInstanceGroups) {
        this.restrictedInstanceGroups = restrictedInstanceGroups != null
                ? restrictedInstanceGroups
                : new ArrayList<>();
    }

    public Map<String, Object> getRestrictedInstanceGroupsConfig() {
        return restrictedInstanceGroupsConfig;
    }

    public void setRestrictedInstanceGroupsConfig(Map<String, Object> restrictedInstanceGroupsConfig) {
        this.restrictedInstanceGroupsConfig = restrictedInstanceGroupsConfig;
    }

    public Map<String, Object> getVpcConfig() {
        return vpcConfig;
    }

    public void setVpcConfig(Map<String, Object> vpcConfig) {
        this.vpcConfig = vpcConfig;
    }

    public Map<String, Object> getOrchestrator() {
        return orchestrator;
    }

    public void setOrchestrator(Map<String, Object> orchestrator) {
        this.orchestrator = orchestrator;
    }

    public Map<String, Object> getTieredStorageConfig() {
        return tieredStorageConfig;
    }

    public void setTieredStorageConfig(Map<String, Object> tieredStorageConfig) {
        this.tieredStorageConfig = tieredStorageConfig;
    }

    public Map<String, Object> getAutoScaling() {
        return autoScaling;
    }

    public void setAutoScaling(Map<String, Object> autoScaling) {
        this.autoScaling = autoScaling;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
