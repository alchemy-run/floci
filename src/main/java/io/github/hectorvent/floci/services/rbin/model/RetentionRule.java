package io.github.hectorvent.floci.services.rbin.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Recycle Bin retention rule. Wire names are PascalCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class RetentionRule {

    private String identifier;
    private String description;
    private String resourceType;
    private JsonNode retentionPeriod;
    private JsonNode resourceTags;
    private JsonNode excludeResourceTags;
    private String status;
    private JsonNode lockConfiguration;
    private String lockState;
    private Long lockEndTime;
    private String ruleArn;
    private Map<String, String> tags = new LinkedHashMap<>();

    public RetentionRule() {
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public JsonNode getRetentionPeriod() {
        return retentionPeriod == null ? null : retentionPeriod.deepCopy();
    }

    public void setRetentionPeriod(JsonNode retentionPeriod) {
        this.retentionPeriod = retentionPeriod == null ? null : retentionPeriod.deepCopy();
    }

    public JsonNode getResourceTags() {
        return resourceTags == null ? null : resourceTags.deepCopy();
    }

    public void setResourceTags(JsonNode resourceTags) {
        this.resourceTags = resourceTags == null ? null : resourceTags.deepCopy();
    }

    public JsonNode getExcludeResourceTags() {
        return excludeResourceTags == null ? null : excludeResourceTags.deepCopy();
    }

    public void setExcludeResourceTags(JsonNode excludeResourceTags) {
        this.excludeResourceTags = excludeResourceTags == null ? null : excludeResourceTags.deepCopy();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNode getLockConfiguration() {
        return lockConfiguration == null ? null : lockConfiguration.deepCopy();
    }

    public void setLockConfiguration(JsonNode lockConfiguration) {
        this.lockConfiguration = lockConfiguration == null ? null : lockConfiguration.deepCopy();
    }

    public String getLockState() {
        return lockState;
    }

    public void setLockState(String lockState) {
        this.lockState = lockState;
    }

    public Long getLockEndTime() {
        return lockEndTime;
    }

    public void setLockEndTime(Long lockEndTime) {
        this.lockEndTime = lockEndTime;
    }

    public String getRuleArn() {
        return ruleArn;
    }

    public void setRuleArn(String ruleArn) {
        this.ruleArn = ruleArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
