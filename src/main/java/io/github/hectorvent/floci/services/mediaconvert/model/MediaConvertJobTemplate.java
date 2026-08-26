package io.github.hectorvent.floci.services.mediaconvert.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Elemental MediaConvert job template. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaConvertJobTemplate {

    private String arn;
    private String name;
    private String description;
    private String category;
    private String type = "CUSTOM";
    private String queue;
    private Integer priority;
    private String statusUpdateInterval;
    private JsonNode accelerationSettings;
    private JsonNode hopDestinations;
    private JsonNode settings;
    private long createdAt;
    private long lastUpdated;
    private String region;
    private Map<String, String> tags = new LinkedHashMap<>();

    public MediaConvertJobTemplate() {
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getStatusUpdateInterval() {
        return statusUpdateInterval;
    }

    public void setStatusUpdateInterval(String statusUpdateInterval) {
        this.statusUpdateInterval = statusUpdateInterval;
    }

    public JsonNode getAccelerationSettings() {
        return accelerationSettings;
    }

    public void setAccelerationSettings(JsonNode accelerationSettings) {
        this.accelerationSettings = accelerationSettings;
    }

    public JsonNode getHopDestinations() {
        return hopDestinations;
    }

    public void setHopDestinations(JsonNode hopDestinations) {
        this.hopDestinations = hopDestinations;
    }

    public JsonNode getSettings() {
        return settings;
    }

    public void setSettings(JsonNode settings) {
        this.settings = settings;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
