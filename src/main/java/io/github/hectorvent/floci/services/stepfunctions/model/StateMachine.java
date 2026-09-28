package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private Double updateDate;
    private String revisionId;
    private String description;
    private JsonNode loggingConfiguration;
    private JsonNode tracingConfiguration;
    private JsonNode encryptionConfiguration;
    private Map<String, String> tags = new HashMap<>();
    private String creationVersionArn;
    private String creationVersionDescription;
    private int versionCounter = 0;
    private List<StateMachineVersion> versions = new ArrayList<>();

    public StateMachine() {
        this.creationDate = System.currentTimeMillis() / 1000.0;
        this.revisionId = UUID.randomUUID().toString();
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

    public Double getUpdateDate() { return updateDate; }
    public void setUpdateDate(Double updateDate) { this.updateDate = updateDate; }

    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public JsonNode getLoggingConfiguration() { return loggingConfiguration; }
    public void setLoggingConfiguration(JsonNode loggingConfiguration) { this.loggingConfiguration = loggingConfiguration; }

    public JsonNode getTracingConfiguration() { return tracingConfiguration; }
    public void setTracingConfiguration(JsonNode tracingConfiguration) { this.tracingConfiguration = tracingConfiguration; }

    public JsonNode getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(JsonNode encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getCreationVersionArn() { return creationVersionArn; }
    public void setCreationVersionArn(String creationVersionArn) {
        this.creationVersionArn = creationVersionArn;
    }

    public String getCreationVersionDescription() { return creationVersionDescription; }
    public void setCreationVersionDescription(String creationVersionDescription) {
        this.creationVersionDescription = creationVersionDescription;
    }

    public int getVersionCounter() { return versionCounter; }
    public void setVersionCounter(int versionCounter) { this.versionCounter = versionCounter; }

    public List<StateMachineVersion> getVersions() { return versions; }
    public void setVersions(List<StateMachineVersion> versions) { this.versions = versions; }

    @JsonProperty("tracingEnabled")
    public void setTracingEnabled(boolean enabled) {
        tracingConfiguration = JsonNodeFactory.instance.objectNode().put("enabled", enabled);
    }

    @JsonProperty("loggingLevel")
    public void setLoggingLevel(String level) {
        legacyLoggingConfiguration().put("level", level);
    }

    @JsonProperty("includeExecutionData")
    public void setIncludeExecutionData(boolean includeExecutionData) {
        legacyLoggingConfiguration().put("includeExecutionData", includeExecutionData);
    }

    @JsonProperty("loggingDestinationsJson")
    public void setLoggingDestinationsJson(String destinations) throws JsonProcessingException {
        if (destinations != null && !destinations.isBlank()) {
            legacyLoggingConfiguration().set("destinations", new ObjectMapper().readTree(destinations));
        }
    }

    private ObjectNode legacyLoggingConfiguration() {
        if (!(loggingConfiguration instanceof ObjectNode)) {
            loggingConfiguration = JsonNodeFactory.instance.objectNode()
                    .put("level", "OFF").put("includeExecutionData", false);
        }
        return (ObjectNode) loggingConfiguration;
    }
}
