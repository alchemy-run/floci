package io.github.hectorvent.floci.services.codeconnections.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeConnectionsSyncConfiguration {

    private String branch;
    private String configFile;
    private String ownerId;
    private String providerType;
    private String repositoryLinkId;
    private String repositoryName;
    private String resourceName;
    private String roleArn;
    private String syncType;
    private String publishDeploymentStatus;
    private String triggerResourceUpdateOn;
    private String pullRequestComment;
    private String region;
    private String accountId;

    public CodeConnectionsSyncConfiguration() {
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getRepositoryLinkId() {
        return repositoryLinkId;
    }

    public void setRepositoryLinkId(String repositoryLinkId) {
        this.repositoryLinkId = repositoryLinkId;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getSyncType() {
        return syncType;
    }

    public void setSyncType(String syncType) {
        this.syncType = syncType;
    }

    public String getPublishDeploymentStatus() {
        return publishDeploymentStatus;
    }

    public void setPublishDeploymentStatus(String publishDeploymentStatus) {
        this.publishDeploymentStatus = publishDeploymentStatus;
    }

    public String getTriggerResourceUpdateOn() {
        return triggerResourceUpdateOn;
    }

    public void setTriggerResourceUpdateOn(String triggerResourceUpdateOn) {
        this.triggerResourceUpdateOn = triggerResourceUpdateOn;
    }

    public String getPullRequestComment() {
        return pullRequestComment;
    }

    public void setPullRequestComment(String pullRequestComment) {
        this.pullRequestComment = pullRequestComment;
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
}
