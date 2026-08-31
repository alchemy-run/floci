package io.github.hectorvent.floci.services.frauddetector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredEvent {

    private String eventId;
    private String eventTypeName;
    private String eventTimestamp;
    private String currentLabel;
    private String labelTimestamp;
    private String region;
    private Map<String, String> eventVariables = new LinkedHashMap<>();
    private List<Map<String, String>> entities = new ArrayList<>();

    public StoredEvent() {}

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getEventTypeName() { return eventTypeName; }
    public void setEventTypeName(String eventTypeName) { this.eventTypeName = eventTypeName; }

    public String getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(String eventTimestamp) { this.eventTimestamp = eventTimestamp; }

    public String getCurrentLabel() { return currentLabel; }
    public void setCurrentLabel(String currentLabel) { this.currentLabel = currentLabel; }

    public String getLabelTimestamp() { return labelTimestamp; }
    public void setLabelTimestamp(String labelTimestamp) { this.labelTimestamp = labelTimestamp; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getEventVariables() { return eventVariables; }
    public void setEventVariables(Map<String, String> eventVariables) {
        this.eventVariables = eventVariables != null ? eventVariables : new LinkedHashMap<>();
    }

    public List<Map<String, String>> getEntities() { return entities; }
    public void setEntities(List<Map<String, String>> entities) {
        this.entities = entities != null ? entities : new ArrayList<>();
    }
}
