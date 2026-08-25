package io.github.hectorvent.floci.services.amplify.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amplify branch. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AmplifyBranch {

    private String branchArn;
    private String branchName;
    private String description;
    private Map<String, String> tags;
    private String stage;
    private String displayName;
    private Boolean enableNotification;
    private long createTime;
    private long updateTime;
    private Map<String, String> environmentVariables;
    private Boolean enableAutoBuild;
    private Boolean enableSkewProtection;
    private String framework;
    private Boolean enableBasicAuth;
    private Boolean enablePerformanceMode;
    private String basicAuthCredentials;
    private String buildSpec;
    private String ttl;
    private Boolean enablePullRequestPreview;
    private String pullRequestEnvironmentName;
    private String backendEnvironmentArn;
    private String computeRoleArn;
    private String activeJobId;
    private String totalNumberOfJobs;
    private int nextJobNumber;
    private Map<String, AmplifyJob> jobs;

    public AmplifyBranch() {
    }

    public String getBranchArn() {
        return branchArn;
    }

    public void setBranchArn(String branchArn) {
        this.branchArn = branchArn;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Boolean getEnableNotification() {
        return enableNotification;
    }

    public void setEnableNotification(Boolean enableNotification) {
        this.enableNotification = enableNotification;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public Map<String, String> getEnvironmentVariables() {
        return environmentVariables;
    }

    public void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(environmentVariables);
    }

    public Boolean getEnableAutoBuild() {
        return enableAutoBuild;
    }

    public void setEnableAutoBuild(Boolean enableAutoBuild) {
        this.enableAutoBuild = enableAutoBuild;
    }

    public Boolean getEnableSkewProtection() {
        return enableSkewProtection;
    }

    public void setEnableSkewProtection(Boolean enableSkewProtection) {
        this.enableSkewProtection = enableSkewProtection;
    }

    public String getFramework() {
        return framework;
    }

    public void setFramework(String framework) {
        this.framework = framework;
    }

    public Boolean getEnableBasicAuth() {
        return enableBasicAuth;
    }

    public void setEnableBasicAuth(Boolean enableBasicAuth) {
        this.enableBasicAuth = enableBasicAuth;
    }

    public Boolean getEnablePerformanceMode() {
        return enablePerformanceMode;
    }

    public void setEnablePerformanceMode(Boolean enablePerformanceMode) {
        this.enablePerformanceMode = enablePerformanceMode;
    }

    public String getBasicAuthCredentials() {
        return basicAuthCredentials;
    }

    public void setBasicAuthCredentials(String basicAuthCredentials) {
        this.basicAuthCredentials = basicAuthCredentials;
    }

    public String getBuildSpec() {
        return buildSpec;
    }

    public void setBuildSpec(String buildSpec) {
        this.buildSpec = buildSpec;
    }

    public String getTtl() {
        return ttl;
    }

    public void setTtl(String ttl) {
        this.ttl = ttl;
    }

    public Boolean getEnablePullRequestPreview() {
        return enablePullRequestPreview;
    }

    public void setEnablePullRequestPreview(Boolean enablePullRequestPreview) {
        this.enablePullRequestPreview = enablePullRequestPreview;
    }

    public String getPullRequestEnvironmentName() {
        return pullRequestEnvironmentName;
    }

    public void setPullRequestEnvironmentName(String pullRequestEnvironmentName) {
        this.pullRequestEnvironmentName = pullRequestEnvironmentName;
    }

    public String getBackendEnvironmentArn() {
        return backendEnvironmentArn;
    }

    public void setBackendEnvironmentArn(String backendEnvironmentArn) {
        this.backendEnvironmentArn = backendEnvironmentArn;
    }

    public String getComputeRoleArn() {
        return computeRoleArn;
    }

    public void setComputeRoleArn(String computeRoleArn) {
        this.computeRoleArn = computeRoleArn;
    }

    public String getActiveJobId() {
        return activeJobId;
    }

    public void setActiveJobId(String activeJobId) {
        this.activeJobId = activeJobId;
    }

    public String getTotalNumberOfJobs() {
        return totalNumberOfJobs;
    }

    public void setTotalNumberOfJobs(String totalNumberOfJobs) {
        this.totalNumberOfJobs = totalNumberOfJobs;
    }

    public int getNextJobNumber() {
        return nextJobNumber;
    }

    public void setNextJobNumber(int nextJobNumber) {
        this.nextJobNumber = nextJobNumber;
    }

    public Map<String, AmplifyJob> getJobs() {
        if (jobs == null) {
            jobs = new LinkedHashMap<>();
        }
        return jobs;
    }

    public void setJobs(Map<String, AmplifyJob> jobs) {
        this.jobs = jobs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(jobs);
    }
}
