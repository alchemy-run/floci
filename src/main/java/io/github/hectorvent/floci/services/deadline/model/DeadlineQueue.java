package io.github.hectorvent.floci.services.deadline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Deadline Cloud queue. Wire JSON is camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeadlineQueue {

    private String farmId;
    private String queueId;
    private String displayName;
    private String description;
    private String status = "IDLE";
    private String defaultBudgetAction = "NONE";
    private JsonNode jobAttachmentSettings;
    private String roleArn;
    private JsonNode jobRunAsUser;
    private List<String> requiredFileSystemLocationNames = new ArrayList<>();
    private List<String> allowedStorageProfileIds = new ArrayList<>();
    private JsonNode schedulingConfiguration;
    private String createdAt;
    private String createdBy;
    private String updatedAt;
    private String updatedBy;
    private String clientToken;
    private String region;
    private String accountId;
    private Map<String, String> tags = new LinkedHashMap<>();

    public DeadlineQueue() {
    }

    public String getFarmId() {
        return farmId;
    }

    public void setFarmId(String farmId) {
        this.farmId = farmId;
    }

    public String getQueueId() {
        return queueId;
    }

    public void setQueueId(String queueId) {
        this.queueId = queueId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDefaultBudgetAction() {
        return defaultBudgetAction;
    }

    public void setDefaultBudgetAction(String defaultBudgetAction) {
        this.defaultBudgetAction = defaultBudgetAction;
    }

    public JsonNode getJobAttachmentSettings() {
        return jobAttachmentSettings;
    }

    public void setJobAttachmentSettings(JsonNode jobAttachmentSettings) {
        this.jobAttachmentSettings = jobAttachmentSettings;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public JsonNode getJobRunAsUser() {
        return jobRunAsUser;
    }

    public void setJobRunAsUser(JsonNode jobRunAsUser) {
        this.jobRunAsUser = jobRunAsUser;
    }

    public List<String> getRequiredFileSystemLocationNames() {
        return requiredFileSystemLocationNames;
    }

    public void setRequiredFileSystemLocationNames(List<String> names) {
        this.requiredFileSystemLocationNames = names == null ? new ArrayList<>() : new ArrayList<>(names);
    }

    public List<String> getAllowedStorageProfileIds() {
        return allowedStorageProfileIds;
    }

    public void setAllowedStorageProfileIds(List<String> ids) {
        this.allowedStorageProfileIds = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }

    public JsonNode getSchedulingConfiguration() {
        return schedulingConfiguration;
    }

    public void setSchedulingConfiguration(JsonNode schedulingConfiguration) {
        this.schedulingConfiguration = schedulingConfiguration;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String arn() {
        return "arn:aws:deadline:" + region + ":" + accountId + ":farm/" + farmId + "/queue/" + queueId;
    }
}
