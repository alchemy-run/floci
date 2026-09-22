package io.github.hectorvent.floci.services.inspector2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InspectorFilter {
    private String arn;
    private String ownerId;
    private String name;
    private JsonNode criteria;
    private String action;
    private double createdAt;
    private double updatedAt;
    private String description;
    private String reason;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public JsonNode getCriteria() { return criteria; }
    public void setCriteria(JsonNode criteria) { this.criteria = criteria; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public double getCreatedAt() { return createdAt; }
    public void setCreatedAt(double createdAt) { this.createdAt = createdAt; }
    public double getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(double updatedAt) { this.updatedAt = updatedAt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public InspectorFilter copy() {
        InspectorFilter copy = new InspectorFilter();
        copy.arn = arn;
        copy.ownerId = ownerId;
        copy.name = name;
        copy.criteria = criteria.deepCopy();
        copy.action = action;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.description = description;
        copy.reason = reason;
        copy.tags = new LinkedHashMap<>(tags);
        return copy;
    }
}
