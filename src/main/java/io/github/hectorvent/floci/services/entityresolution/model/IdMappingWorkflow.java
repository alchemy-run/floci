package io.github.hectorvent.floci.services.entityresolution.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Entity Resolution ID mapping workflow. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IdMappingWorkflow {

    private String workflowName;
    private String workflowArn;
    private String description;
    private JsonNode inputSourceConfig;
    private JsonNode outputSourceConfig;
    private JsonNode idMappingTechniques;
    private JsonNode incrementalRunConfig;
    private String roleArn;
    private Map<String, String> tags;
    private long createdAt;
    private long updatedAt;
    private Map<String, IdMappingJob> jobs;

    public IdMappingWorkflow() {
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

    public JsonNode getIdMappingTechniques() {
        return idMappingTechniques;
    }

    public void setIdMappingTechniques(JsonNode idMappingTechniques) {
        this.idMappingTechniques = idMappingTechniques;
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

    public Map<String, IdMappingJob> getJobs() {
        return jobs;
    }

    public void setJobs(Map<String, IdMappingJob> jobs) {
        this.jobs = jobs == null ? null : new LinkedHashMap<>(jobs);
    }
}
