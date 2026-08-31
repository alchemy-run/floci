package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon EMR Serverless job run. Wire names are camelCase. */
@RegisterForReflection
public class JobRun {

    private String applicationId;
    private String jobRunId;
    private String name;
    private String arn;
    private String createdBy;
    private long createdAt;
    private long updatedAt;
    private String executionRole;
    private String state;
    private String stateDetails;
    private String releaseLabel;
    private Map<String, Object> jobDriver = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private Integer executionTimeoutMinutes;
    private String region;
    private String clientToken;
    private int attempt = 1;

    public JobRun() {
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String getJobRunId() {
        return jobRunId;
    }

    public void setJobRunId(String jobRunId) {
        this.jobRunId = jobRunId;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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

    public String getExecutionRole() {
        return executionRole;
    }

    public void setExecutionRole(String executionRole) {
        this.executionRole = executionRole;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateDetails() {
        return stateDetails;
    }

    public void setStateDetails(String stateDetails) {
        this.stateDetails = stateDetails;
    }

    public String getReleaseLabel() {
        return releaseLabel;
    }

    public void setReleaseLabel(String releaseLabel) {
        this.releaseLabel = releaseLabel;
    }

    public Map<String, Object> getJobDriver() {
        return jobDriver;
    }

    public void setJobDriver(Map<String, Object> jobDriver) {
        this.jobDriver = jobDriver == null ? new LinkedHashMap<>() : jobDriver;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public Integer getExecutionTimeoutMinutes() {
        return executionTimeoutMinutes;
    }

    public void setExecutionTimeoutMinutes(Integer executionTimeoutMinutes) {
        this.executionTimeoutMinutes = executionTimeoutMinutes;
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

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }
}
