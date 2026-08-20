package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Connection {
    @JsonProperty("Name")
    private String name;
    @JsonProperty("ConnectionType")
    private String connectionType;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("ConnectionProperties")
    private Map<String, String> connectionProperties;
    @JsonProperty("MatchCriteria")
    private List<String> matchCriteria;
    @JsonProperty("PhysicalConnectionRequirements")
    private Map<String, Object> physicalConnectionRequirements;
    @JsonProperty("CreationTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant creationTime;
    @JsonProperty("LastUpdatedTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastUpdatedTime;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConnectionType() { return connectionType; }
    public void setConnectionType(String connectionType) { this.connectionType = connectionType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, String> getConnectionProperties() { return connectionProperties; }
    public void setConnectionProperties(Map<String, String> connectionProperties) {
        this.connectionProperties = connectionProperties;
    }
    public List<String> getMatchCriteria() { return matchCriteria; }
    public void setMatchCriteria(List<String> matchCriteria) { this.matchCriteria = matchCriteria; }
    public Map<String, Object> getPhysicalConnectionRequirements() { return physicalConnectionRequirements; }
    public void setPhysicalConnectionRequirements(Map<String, Object> physicalConnectionRequirements) {
        this.physicalConnectionRequirements = physicalConnectionRequirements;
    }
    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
    public Instant getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(Instant lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }

    public Connection withoutPassword() {
        Connection copy = new Connection();
        copy.name = name;
        copy.connectionType = connectionType;
        copy.description = description;
        copy.matchCriteria = matchCriteria;
        copy.physicalConnectionRequirements = physicalConnectionRequirements;
        copy.creationTime = creationTime;
        copy.lastUpdatedTime = lastUpdatedTime;
        if (connectionProperties != null) {
            Map<String, String> props = new LinkedHashMap<>(connectionProperties);
            props.remove("PASSWORD");
            copy.connectionProperties = props;
        }
        return copy;
    }
}
