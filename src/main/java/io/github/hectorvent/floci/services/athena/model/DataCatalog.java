package io.github.hectorvent.floci.services.athena.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DataCatalog {
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Type")
    private String type;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("Parameters")
    private Map<String, String> parameters = new LinkedHashMap<>();
    @JsonProperty("Tags")
    private List<WorkGroupTag> tags = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
    }
    public List<WorkGroupTag> getTags() { return tags; }
    public void setTags(List<WorkGroupTag> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }
}
