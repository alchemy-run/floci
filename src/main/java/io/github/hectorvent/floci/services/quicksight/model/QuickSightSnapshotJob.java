package io.github.hectorvent.floci.services.quicksight.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class QuickSightSnapshotJob {

    private String snapshotJobId;
    private String dashboardId;
    private String awsAccountId;
    private String arn;
    private String jobStatus;
    private long createdTime;
    private long lastUpdatedTime;
    private JsonNode userConfiguration;
    private JsonNode snapshotConfiguration;

    public String getSnapshotJobId() {
        return snapshotJobId;
    }

    public void setSnapshotJobId(String snapshotJobId) {
        this.snapshotJobId = snapshotJobId;
    }

    public String getDashboardId() {
        return dashboardId;
    }

    public void setDashboardId(String dashboardId) {
        this.dashboardId = dashboardId;
    }

    public String getAwsAccountId() {
        return awsAccountId;
    }

    public void setAwsAccountId(String awsAccountId) {
        this.awsAccountId = awsAccountId;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getJobStatus() {
        return jobStatus;
    }

    public void setJobStatus(String jobStatus) {
        this.jobStatus = jobStatus;
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

    public JsonNode getUserConfiguration() {
        return userConfiguration;
    }

    public void setUserConfiguration(JsonNode userConfiguration) {
        this.userConfiguration = userConfiguration == null ? null : userConfiguration.deepCopy();
    }

    public JsonNode getSnapshotConfiguration() {
        return snapshotConfiguration;
    }

    public void setSnapshotConfiguration(JsonNode snapshotConfiguration) {
        this.snapshotConfiguration = snapshotConfiguration == null ? null : snapshotConfiguration.deepCopy();
    }
}
