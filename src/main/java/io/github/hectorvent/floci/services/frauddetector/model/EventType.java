package io.github.hectorvent.floci.services.frauddetector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventType {

    private String name;
    private String description;
    private String arn;
    private String createdTime;
    private String lastUpdatedTime;
    private String region;
    private String eventIngestion;
    private Boolean eventBridgeEnabled;
    private List<String> eventVariables = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private List<String> entityTypes = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public EventType() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    public String getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(String lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getEventIngestion() { return eventIngestion; }
    public void setEventIngestion(String eventIngestion) { this.eventIngestion = eventIngestion; }

    public Boolean getEventBridgeEnabled() { return eventBridgeEnabled; }
    public void setEventBridgeEnabled(Boolean eventBridgeEnabled) { this.eventBridgeEnabled = eventBridgeEnabled; }

    public List<String> getEventVariables() { return eventVariables; }
    public void setEventVariables(List<String> eventVariables) {
        this.eventVariables = eventVariables != null ? eventVariables : new ArrayList<>();
    }

    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) {
        this.labels = labels != null ? labels : new ArrayList<>();
    }

    public List<String> getEntityTypes() { return entityTypes; }
    public void setEntityTypes(List<String> entityTypes) {
        this.entityTypes = entityTypes != null ? entityTypes : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
