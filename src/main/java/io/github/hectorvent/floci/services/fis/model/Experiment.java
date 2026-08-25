package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A running or completed AWS FIS experiment. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Experiment {

    private String id;
    private String arn;
    private String experimentTemplateId;
    private String roleArn;
    private String clientToken;
    private String status;
    private String statusReason;
    private JsonNode actions;
    private JsonNode targets;
    private JsonNode stopConditions;
    private JsonNode experimentOptions;
    private JsonNode logConfiguration;
    private JsonNode experimentReportConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, TargetAccountConfiguration> targetAccountConfigurations = new LinkedHashMap<>();
    private long creationTime;
    private Long startTime;
    private Long endTime;

    public Experiment() {
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

    public String getExperimentTemplateId() {
        return experimentTemplateId;
    }

    public void setExperimentTemplateId(String experimentTemplateId) {
        this.experimentTemplateId = experimentTemplateId;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public JsonNode getActions() {
        return actions;
    }

    public void setActions(JsonNode actions) {
        this.actions = actions;
    }

    public JsonNode getTargets() {
        return targets;
    }

    public void setTargets(JsonNode targets) {
        this.targets = targets;
    }

    public JsonNode getStopConditions() {
        return stopConditions;
    }

    public void setStopConditions(JsonNode stopConditions) {
        this.stopConditions = stopConditions;
    }

    public JsonNode getExperimentOptions() {
        return experimentOptions;
    }

    public void setExperimentOptions(JsonNode experimentOptions) {
        this.experimentOptions = experimentOptions;
    }

    public JsonNode getLogConfiguration() {
        return logConfiguration;
    }

    public void setLogConfiguration(JsonNode logConfiguration) {
        this.logConfiguration = logConfiguration;
    }

    public JsonNode getExperimentReportConfiguration() {
        return experimentReportConfiguration;
    }

    public void setExperimentReportConfiguration(JsonNode experimentReportConfiguration) {
        this.experimentReportConfiguration = experimentReportConfiguration;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public Map<String, TargetAccountConfiguration> getTargetAccountConfigurations() {
        return targetAccountConfigurations;
    }

    public void setTargetAccountConfigurations(Map<String, TargetAccountConfiguration> targetAccountConfigurations) {
        this.targetAccountConfigurations = targetAccountConfigurations == null
                ? new LinkedHashMap<>()
                : targetAccountConfigurations;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }
}
