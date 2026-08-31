package io.github.hectorvent.floci.services.frauddetector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class FraudList {

    private String name;
    private String description;
    private String variableType;
    private String arn;
    private String createdTime;
    private String updatedTime;
    private String region;
    private List<String> elements = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public FraudList() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVariableType() { return variableType; }
    public void setVariableType(String variableType) { this.variableType = variableType; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getCreatedTime() { return createdTime; }
    public void setCreatedTime(String createdTime) { this.createdTime = createdTime; }

    public String getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(String updatedTime) { this.updatedTime = updatedTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<String> getElements() { return elements; }
    public void setElements(List<String> elements) {
        this.elements = elements != null ? elements : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
