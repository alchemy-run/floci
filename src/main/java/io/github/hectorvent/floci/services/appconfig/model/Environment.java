package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Environment {
    @JsonProperty("Id")
    private String id;
    @JsonProperty("ApplicationId")
    private String applicationId;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("State")
    private String state; // READY, DEPLOYING, ROLLING_BACK, ROLLED_BACK
    @JsonProperty("Monitors")
    private List<Map<String, Object>> monitors;
    @JsonIgnore
    private Map<String, String> tags = new HashMap<>();

    public Environment() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public List<Map<String, Object>> getMonitors() { return monitors; }
    public void setMonitors(List<Map<String, Object>> monitors) { this.monitors = monitors; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
}
