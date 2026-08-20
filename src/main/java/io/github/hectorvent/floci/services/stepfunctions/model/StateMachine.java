package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StateMachine {
    private String stateMachineArn;
    private String name;
    private String definition;
    private String roleArn;
    private String type = "STANDARD";
    private String status = "ACTIVE";
    private double creationDate;
    private String revisionId;
    private boolean tracingEnabled;
    private String loggingLevel = "OFF";
    private boolean includeExecutionData;
    private String loggingDestinationsJson;
    private Map<String, String> tags = new HashMap<>();
    private int versionCounter = 0;
    private List<StateMachineVersion> versions = new ArrayList<>();

    public StateMachine() {
        this.creationDate = System.currentTimeMillis() / 1000.0;
        this.revisionId = java.util.UUID.randomUUID().toString();
    }

    public String getStateMachineArn() { return stateMachineArn; }
    public void setStateMachineArn(String stateMachineArn) { this.stateMachineArn = stateMachineArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public double getCreationDate() { return creationDate; }
    public void setCreationDate(double creationDate) { this.creationDate = creationDate; }

    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }

    public boolean isTracingEnabled() { return tracingEnabled; }
    public void setTracingEnabled(boolean tracingEnabled) { this.tracingEnabled = tracingEnabled; }

    public String getLoggingLevel() { return loggingLevel; }
    public void setLoggingLevel(String loggingLevel) { this.loggingLevel = loggingLevel; }

    public boolean isIncludeExecutionData() { return includeExecutionData; }
    public void setIncludeExecutionData(boolean includeExecutionData) { this.includeExecutionData = includeExecutionData; }

    public String getLoggingDestinationsJson() { return loggingDestinationsJson; }
    public void setLoggingDestinationsJson(String loggingDestinationsJson) { this.loggingDestinationsJson = loggingDestinationsJson; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public int getVersionCounter() { return versionCounter; }
    public void setVersionCounter(int versionCounter) { this.versionCounter = versionCounter; }

    public List<StateMachineVersion> getVersions() { return versions; }
    public void setVersions(List<StateMachineVersion> versions) { this.versions = versions; }
}
