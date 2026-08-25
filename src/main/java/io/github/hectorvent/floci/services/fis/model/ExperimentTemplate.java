package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS FIS experiment template. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExperimentTemplate {

    private String id;
    private String arn;
    private String description;
    private String roleArn;
    private String region;
    private String accountId;
    private String clientToken;
    private long creationTime;
    private long lastUpdateTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, Object> targets = new LinkedHashMap<>();
    private Map<String, Object> actions = new LinkedHashMap<>();
    private List<Map<String, Object>> stopConditions;
    private Map<String, Object> logConfiguration;
    private Map<String, Object> experimentOptions;
    private Map<String, Object> experimentReportConfiguration;

    public ExperimentTemplate() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
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

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public void setLastUpdateTime(long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Map<String, Object> getTargets() {
        return targets;
    }

    public void setTargets(Map<String, Object> targets) {
        this.targets = targets == null ? new LinkedHashMap<>() : new LinkedHashMap<>(targets);
    }

    public Map<String, Object> getActions() {
        return actions;
    }

    public void setActions(Map<String, Object> actions) {
        this.actions = actions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(actions);
    }

    public List<Map<String, Object>> getStopConditions() {
        return stopConditions;
    }

    public void setStopConditions(List<Map<String, Object>> stopConditions) {
        this.stopConditions = stopConditions;
    }

    public Map<String, Object> getLogConfiguration() {
        return logConfiguration;
    }

    public void setLogConfiguration(Map<String, Object> logConfiguration) {
        this.logConfiguration = logConfiguration;
    }

    public Map<String, Object> getExperimentOptions() {
        return experimentOptions;
    }

    public void setExperimentOptions(Map<String, Object> experimentOptions) {
        this.experimentOptions = experimentOptions;
    }

    public Map<String, Object> getExperimentReportConfiguration() {
        return experimentReportConfiguration;
    }

    public void setExperimentReportConfiguration(Map<String, Object> experimentReportConfiguration) {
        this.experimentReportConfiguration = experimentReportConfiguration;
    }
}
