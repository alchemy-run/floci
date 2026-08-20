package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Extension {
    @JsonProperty("Id")
    private String id;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("VersionNumber")
    private int versionNumber;
    @JsonProperty("Arn")
    private String arn;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("Actions")
    private Object actions;
    @JsonProperty("Parameters")
    private Object parameters;
    @JsonIgnore
    private Map<String, String> tags = new HashMap<>();

    public Extension() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Object getActions() { return actions; }
    public void setActions(Object actions) { this.actions = actions; }

    public Object getParameters() { return parameters; }
    public void setParameters(Object parameters) { this.parameters = parameters; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
}
