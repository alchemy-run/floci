package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Bedrock AgentCore Memory. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryResource {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String encryptionKeyArn;
    private String memoryExecutionRoleArn;
    private Integer eventExpiryDuration;
    private String status;
    private String failureReason;
    private long createdAt;
    private long updatedAt;
    private JsonNode strategies;
    private Map<String, String> tags;
    private List<MemoryEvent> events = new ArrayList<>();
    private List<MemoryRecordItem> records = new ArrayList<>();
    private List<JsonNode> extractionJobs = new ArrayList<>();

    public MemoryResource() {
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

    public String getEncryptionKeyArn() {
        return encryptionKeyArn;
    }

    public void setEncryptionKeyArn(String encryptionKeyArn) {
        this.encryptionKeyArn = encryptionKeyArn;
    }

    public String getMemoryExecutionRoleArn() {
        return memoryExecutionRoleArn;
    }

    public void setMemoryExecutionRoleArn(String memoryExecutionRoleArn) {
        this.memoryExecutionRoleArn = memoryExecutionRoleArn;
    }

    public Integer getEventExpiryDuration() {
        return eventExpiryDuration;
    }

    public void setEventExpiryDuration(Integer eventExpiryDuration) {
        this.eventExpiryDuration = eventExpiryDuration;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public JsonNode getStrategies() {
        return strategies == null ? null : strategies.deepCopy();
    }

    public void setStrategies(JsonNode strategies) {
        this.strategies = strategies == null ? null : strategies.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags == null ? null : Map.copyOf(tags);
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }

    public List<MemoryEvent> getEvents() {
        return events;
    }

    public void setEvents(List<MemoryEvent> events) {
        this.events = events == null ? new ArrayList<>() : events;
    }

    public List<MemoryRecordItem> getRecords() {
        return records;
    }

    public void setRecords(List<MemoryRecordItem> records) {
        this.records = records == null ? new ArrayList<>() : records;
    }

    public List<JsonNode> getExtractionJobs() {
        return extractionJobs;
    }

    public void setExtractionJobs(List<JsonNode> extractionJobs) {
        this.extractionJobs = extractionJobs == null ? new ArrayList<>() : extractionJobs;
    }
}
