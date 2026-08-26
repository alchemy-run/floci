package io.github.hectorvent.floci.services.mediaconvert.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Elemental MediaConvert transcode job. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaConvertJob {

    private String id;
    private String arn;
    private String role;
    private String queue;
    private String status = "SUBMITTED";
    private Integer priority;
    private String jobTemplate;
    private JsonNode settings;
    private JsonNode accelerationSettings;
    private JsonNode userMetadata;
    private long createdAt;
    private String region;
    private Map<String, String> tags = new LinkedHashMap<>();

    public MediaConvertJob() {
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getJobTemplate() {
        return jobTemplate;
    }

    public void setJobTemplate(String jobTemplate) {
        this.jobTemplate = jobTemplate;
    }

    public JsonNode getSettings() {
        return settings;
    }

    public void setSettings(JsonNode settings) {
        this.settings = settings;
    }

    public JsonNode getAccelerationSettings() {
        return accelerationSettings;
    }

    public void setAccelerationSettings(JsonNode accelerationSettings) {
        this.accelerationSettings = accelerationSettings;
    }

    public JsonNode getUserMetadata() {
        return userMetadata;
    }

    public void setUserMetadata(JsonNode userMetadata) {
        this.userMetadata = userMetadata;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
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
