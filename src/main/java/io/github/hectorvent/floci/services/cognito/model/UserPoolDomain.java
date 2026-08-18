package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPoolDomain {
    private String userPoolId;
    private String domain;
    private String status = "ACTIVE";
    private String cloudFrontDistribution;
    private String awsAccountId;
    private Integer managedLoginVersion = 2;
    private Map<String, Object> customDomainConfig = new HashMap<>();
    private boolean custom;
    private long creationDate;
    private long lastModifiedDate;

    public UserPoolDomain() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
    }

    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCloudFrontDistribution() { return cloudFrontDistribution; }
    public void setCloudFrontDistribution(String cloudFrontDistribution) {
        this.cloudFrontDistribution = cloudFrontDistribution;
    }

    public String getAwsAccountId() { return awsAccountId; }
    public void setAwsAccountId(String awsAccountId) { this.awsAccountId = awsAccountId; }

    public Integer getManagedLoginVersion() { return managedLoginVersion; }
    public void setManagedLoginVersion(Integer managedLoginVersion) {
        this.managedLoginVersion = managedLoginVersion;
    }

    public Map<String, Object> getCustomDomainConfig() { return customDomainConfig; }
    public void setCustomDomainConfig(Map<String, Object> customDomainConfig) {
        this.customDomainConfig = customDomainConfig == null ? new HashMap<>() : new HashMap<>(customDomainConfig);
    }

    public boolean isCustom() { return custom; }
    public void setCustom(boolean custom) { this.custom = custom; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
