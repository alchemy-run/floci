package io.github.hectorvent.floci.services.amplify.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amplify Hosting app. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AmplifyApp {

    private String appId;
    private String appArn;
    private String name;
    private Map<String, String> tags;
    private String description;
    private String platform;
    private long createTime;
    private long updateTime;
    private Map<String, String> environmentVariables;
    private String defaultDomain;
    private Boolean enableBranchAutoBuild;
    private Boolean enableBranchAutoDeletion;
    private Boolean enableBasicAuth;
    private String basicAuthCredentials;
    private JsonNode customRules;
    private String buildSpec;
    private String customHeaders;
    private Boolean enableAutoBranchCreation;
    private String computeRoleArn;
    private String iamServiceRoleArn;
    private String repository;
    private Map<String, AmplifyBranch> branches;

    public AmplifyApp() {
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppArn() {
        return appArn;
    }

    public void setAppArn(String appArn) {
        this.appArn = appArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
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

    public String getDefaultDomain() {
        return defaultDomain;
    }

    public void setDefaultDomain(String defaultDomain) {
        this.defaultDomain = defaultDomain;
    }

    public Boolean getEnableBranchAutoBuild() {
        return enableBranchAutoBuild;
    }

    public void setEnableBranchAutoBuild(Boolean enableBranchAutoBuild) {
        this.enableBranchAutoBuild = enableBranchAutoBuild;
    }

    public Boolean getEnableBranchAutoDeletion() {
        return enableBranchAutoDeletion;
    }

    public void setEnableBranchAutoDeletion(Boolean enableBranchAutoDeletion) {
        this.enableBranchAutoDeletion = enableBranchAutoDeletion;
    }

    public Boolean getEnableBasicAuth() {
        return enableBasicAuth;
    }

    public void setEnableBasicAuth(Boolean enableBasicAuth) {
        this.enableBasicAuth = enableBasicAuth;
    }

    public String getBasicAuthCredentials() {
        return basicAuthCredentials;
    }

    public void setBasicAuthCredentials(String basicAuthCredentials) {
        this.basicAuthCredentials = basicAuthCredentials;
    }

    public JsonNode getCustomRules() {
        return customRules;
    }

    public void setCustomRules(JsonNode customRules) {
        this.customRules = customRules == null ? null : customRules.deepCopy();
    }

    public String getBuildSpec() {
        return buildSpec;
    }

    public void setBuildSpec(String buildSpec) {
        this.buildSpec = buildSpec;
    }

    public String getCustomHeaders() {
        return customHeaders;
    }

    public void setCustomHeaders(String customHeaders) {
        this.customHeaders = customHeaders;
    }

    public Boolean getEnableAutoBranchCreation() {
        return enableAutoBranchCreation;
    }

    public void setEnableAutoBranchCreation(Boolean enableAutoBranchCreation) {
        this.enableAutoBranchCreation = enableAutoBranchCreation;
    }

    public String getComputeRoleArn() {
        return computeRoleArn;
    }

    public void setComputeRoleArn(String computeRoleArn) {
        this.computeRoleArn = computeRoleArn;
    }

    public String getIamServiceRoleArn() {
        return iamServiceRoleArn;
    }

    public void setIamServiceRoleArn(String iamServiceRoleArn) {
        this.iamServiceRoleArn = iamServiceRoleArn;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public Map<String, AmplifyBranch> getBranches() {
        if (branches == null) {
            branches = new LinkedHashMap<>();
        }
        return branches;
    }

    public void setBranches(Map<String, AmplifyBranch> branches) {
        this.branches = branches == null ? new LinkedHashMap<>() : new LinkedHashMap<>(branches);
    }
}
