package io.github.hectorvent.floci.services.appflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon AppFlow flow. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Flow {

    private String flowName;
    private String flowArn;
    private String description;
    private String kmsArn;
    private String flowStatus;
    private String flowStatusMessage;
    private JsonNode triggerConfig;
    private JsonNode sourceFlowConfig;
    private JsonNode destinationFlowConfigList;
    private JsonNode tasks;
    private JsonNode metadataCatalogConfig;
    private Map<String, String> tags;
    private long createdAt;
    private long lastUpdatedAt;
    private String createdBy;
    private String lastUpdatedBy;
    private int schemaVersion;
    private JsonNode lastRunExecutionDetails;

    public Flow() {
    }

    public String getFlowName() {
        return flowName;
    }

    public void setFlowName(String flowName) {
        this.flowName = flowName;
    }

    public String getFlowArn() {
        return flowArn;
    }

    public void setFlowArn(String flowArn) {
        this.flowArn = flowArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getKmsArn() {
        return kmsArn;
    }

    public void setKmsArn(String kmsArn) {
        this.kmsArn = kmsArn;
    }

    public String getFlowStatus() {
        return flowStatus;
    }

    public void setFlowStatus(String flowStatus) {
        this.flowStatus = flowStatus;
    }

    public String getFlowStatusMessage() {
        return flowStatusMessage;
    }

    public void setFlowStatusMessage(String flowStatusMessage) {
        this.flowStatusMessage = flowStatusMessage;
    }

    public JsonNode getTriggerConfig() {
        return triggerConfig;
    }

    public void setTriggerConfig(JsonNode triggerConfig) {
        this.triggerConfig = triggerConfig;
    }

    public JsonNode getSourceFlowConfig() {
        return sourceFlowConfig;
    }

    public void setSourceFlowConfig(JsonNode sourceFlowConfig) {
        this.sourceFlowConfig = sourceFlowConfig;
    }

    public JsonNode getDestinationFlowConfigList() {
        return destinationFlowConfigList;
    }

    public void setDestinationFlowConfigList(JsonNode destinationFlowConfigList) {
        this.destinationFlowConfigList = destinationFlowConfigList;
    }

    public JsonNode getTasks() {
        return tasks;
    }

    public void setTasks(JsonNode tasks) {
        this.tasks = tasks;
    }

    public JsonNode getMetadataCatalogConfig() {
        return metadataCatalogConfig;
    }

    public void setMetadataCatalogConfig(JsonNode metadataCatalogConfig) {
        this.metadataCatalogConfig = metadataCatalogConfig;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(long lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getLastUpdatedBy() {
        return lastUpdatedBy;
    }

    public void setLastUpdatedBy(String lastUpdatedBy) {
        this.lastUpdatedBy = lastUpdatedBy;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public JsonNode getLastRunExecutionDetails() {
        return lastRunExecutionDetails;
    }

    public void setLastRunExecutionDetails(JsonNode lastRunExecutionDetails) {
        this.lastRunExecutionDetails = lastRunExecutionDetails;
    }
}
