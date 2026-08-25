package io.github.hectorvent.floci.services.frauddetector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Detector {

    private String detectorId;
    private String description;
    private String eventTypeName;
    private String arn;
    private String createdTime;
    private String lastUpdatedTime;
    private String region;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<DetectorVersion> versions = new ArrayList<>();
    private List<Rule> rules = new ArrayList<>();

    public Detector() {}

    public String getDetectorId() { return detectorId; }
    public void setDetectorId(String detectorId) { this.detectorId = detectorId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEventTypeName() { return eventTypeName; }
    public void setEventTypeName(String eventTypeName) { this.eventTypeName = eventTypeName; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    public String getLastUpdatedTime() { return lastUpdatedTime; }
    public void setLastUpdatedTime(String lastUpdatedTime) { this.lastUpdatedTime = lastUpdatedTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public List<DetectorVersion> getVersions() { return versions; }
    public void setVersions(List<DetectorVersion> versions) {
        this.versions = versions != null ? versions : new ArrayList<>();
    }

    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
    }
}
