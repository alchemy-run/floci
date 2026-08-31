package io.github.hectorvent.floci.services.entityresolution.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Entity Resolution matching workflow. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatchingWorkflow {

    private String workflowName;
    private String workflowArn;
    private String description;
    private JsonNode inputSourceConfig;
    private JsonNode outputSourceConfig;
    private JsonNode resolutionTechniques;
    private JsonNode incrementalRunConfig;
    private String roleArn;
    private Map<String, String> tags;
    private long createdAt;
    private long updatedAt;
    private Map<String, MatchingJob> jobs;
    private Map<String, MatchEntry> matchIndex;

    public MatchingWorkflow() {
    }

    public String getWorkflowName() {
        return workflowName;
    }

    public void setWorkflowName(String workflowName) {
        this.workflowName = workflowName;
    }

    public String getWorkflowArn() {
        return workflowArn;
    }

    public void setWorkflowArn(String workflowArn) {
        this.workflowArn = workflowArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getInputSourceConfig() {
        return inputSourceConfig;
    }

    public void setInputSourceConfig(JsonNode inputSourceConfig) {
        this.inputSourceConfig = inputSourceConfig;
    }

    public JsonNode getOutputSourceConfig() {
        return outputSourceConfig;
    }

    public void setOutputSourceConfig(JsonNode outputSourceConfig) {
        this.outputSourceConfig = outputSourceConfig;
    }

    public JsonNode getResolutionTechniques() {
        return resolutionTechniques;
    }

    public void setResolutionTechniques(JsonNode resolutionTechniques) {
        this.resolutionTechniques = resolutionTechniques;
    }

    public JsonNode getIncrementalRunConfig() {
        return incrementalRunConfig;
    }

    public void setIncrementalRunConfig(JsonNode incrementalRunConfig) {
        this.incrementalRunConfig = incrementalRunConfig;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
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

    public Map<String, MatchingJob> getJobs() {
        return jobs;
    }

    public void setJobs(Map<String, MatchingJob> jobs) {
        this.jobs = jobs == null ? null : new LinkedHashMap<>(jobs);
    }

    public Map<String, MatchEntry> getMatchIndex() {
        return matchIndex;
    }

    public void setMatchIndex(Map<String, MatchEntry> matchIndex) {
        this.matchIndex = matchIndex == null ? null : new LinkedHashMap<>(matchIndex);
    }
}
