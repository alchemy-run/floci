package io.github.hectorvent.floci.services.quicksight.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class QuickSightDashboard {

    private String dashboardId;
    private String name;
    private String arn;
    private String region;
    private String accountId;
    private long createdTime;
    private long lastUpdatedTime;
    private long lastPublishedTime;
    private int versionNumber;
    private String versionStatus;
    private JsonNode definition;
    private JsonNode sourceEntity;
    private JsonNode parameters;
    private JsonNode permissions;
    private JsonNode dashboardPublishOptions;
    private String themeArn;
    private String versionDescription;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, QuickSightSnapshotJob> snapshotJobs = new LinkedHashMap<>();

    public String getDashboardId() {
        return dashboardId;
    }

    public void setDashboardId(String dashboardId) {
        this.dashboardId = dashboardId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
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

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }

    public long getLastPublishedTime() {
        return lastPublishedTime;
    }

    public void setLastPublishedTime(long lastPublishedTime) {
        this.lastPublishedTime = lastPublishedTime;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(int versionNumber) {
        this.versionNumber = versionNumber;
    }

    public String getVersionStatus() {
        return versionStatus;
    }

    public void setVersionStatus(String versionStatus) {
        this.versionStatus = versionStatus;
    }

    public JsonNode getDefinition() {
        return definition;
    }

    public void setDefinition(JsonNode definition) {
        this.definition = definition == null ? null : definition.deepCopy();
    }

    public JsonNode getSourceEntity() {
        return sourceEntity;
    }

    public void setSourceEntity(JsonNode sourceEntity) {
        this.sourceEntity = sourceEntity == null ? null : sourceEntity.deepCopy();
    }

    public JsonNode getParameters() {
        return parameters;
    }

    public void setParameters(JsonNode parameters) {
        this.parameters = parameters == null ? null : parameters.deepCopy();
    }

    public JsonNode getPermissions() {
        return permissions;
    }

    public void setPermissions(JsonNode permissions) {
        this.permissions = permissions == null ? null : permissions.deepCopy();
    }

    public JsonNode getDashboardPublishOptions() {
        return dashboardPublishOptions;
    }

    public void setDashboardPublishOptions(JsonNode dashboardPublishOptions) {
        this.dashboardPublishOptions = dashboardPublishOptions == null
                ? null
                : dashboardPublishOptions.deepCopy();
    }

    public String getThemeArn() {
        return themeArn;
    }

    public void setThemeArn(String themeArn) {
        this.themeArn = themeArn;
    }

    public String getVersionDescription() {
        return versionDescription;
    }

    public void setVersionDescription(String versionDescription) {
        this.versionDescription = versionDescription;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Map<String, QuickSightSnapshotJob> getSnapshotJobs() {
        return snapshotJobs;
    }

    public void setSnapshotJobs(Map<String, QuickSightSnapshotJob> snapshotJobs) {
        this.snapshotJobs = snapshotJobs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(snapshotJobs);
    }
}
