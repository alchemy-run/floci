package io.github.hectorvent.floci.services.greengrassv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Greengrass V2 deployment revision targeting an IoT thing or thing group. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Deployment {

    private String deploymentId;
    private String targetArn;
    private String deploymentName;
    private String revisionId;
    private String deploymentStatus;
    private JsonNode components;
    private long creationTimestamp;
    private boolean latestForTarget;
    private String parentTargetArn;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String region;
    private String clientToken;

    public Deployment() {
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getTargetArn() {
        return targetArn;
    }

    public void setTargetArn(String targetArn) {
        this.targetArn = targetArn;
    }

    public String getDeploymentName() {
        return deploymentName;
    }

    public void setDeploymentName(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    public String getRevisionId() {
        return revisionId;
    }

    public void setRevisionId(String revisionId) {
        this.revisionId = revisionId;
    }

    public String getDeploymentStatus() {
        return deploymentStatus;
    }

    public void setDeploymentStatus(String deploymentStatus) {
        this.deploymentStatus = deploymentStatus;
    }

    public JsonNode getComponents() {
        return components;
    }

    public void setComponents(JsonNode components) {
        this.components = components;
    }

    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    public void setCreationTimestamp(long creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }

    public boolean isLatestForTarget() {
        return latestForTarget;
    }

    public void setLatestForTarget(boolean latestForTarget) {
        this.latestForTarget = latestForTarget;
    }

    public String getParentTargetArn() {
        return parentTargetArn;
    }

    public void setParentTargetArn(String parentTargetArn) {
        this.parentTargetArn = parentTargetArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }
}
