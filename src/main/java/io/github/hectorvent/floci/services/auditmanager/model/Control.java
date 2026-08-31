package io.github.hectorvent.floci.services.auditmanager.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS Audit Manager custom control. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Control {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String testingInformation;
    private String actionPlanTitle;
    private String actionPlanInstructions;
    private String type;
    private String state;
    private String controlSources;
    private JsonNode controlMappingSources;
    private long createdAt;
    private long lastUpdatedAt;
    private String createdBy;
    private String lastUpdatedBy;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Control() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTestingInformation() {
        return testingInformation;
    }

    public void setTestingInformation(String testingInformation) {
        this.testingInformation = testingInformation;
    }

    public String getActionPlanTitle() {
        return actionPlanTitle;
    }

    public void setActionPlanTitle(String actionPlanTitle) {
        this.actionPlanTitle = actionPlanTitle;
    }

    public String getActionPlanInstructions() {
        return actionPlanInstructions;
    }

    public void setActionPlanInstructions(String actionPlanInstructions) {
        this.actionPlanInstructions = actionPlanInstructions;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getControlSources() {
        return controlSources;
    }

    public void setControlSources(String controlSources) {
        this.controlSources = controlSources;
    }

    public JsonNode getControlMappingSources() {
        return controlMappingSources;
    }

    public void setControlMappingSources(JsonNode controlMappingSources) {
        this.controlMappingSources = controlMappingSources;
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

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
