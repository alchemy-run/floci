package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtensionAssociation {
    @JsonProperty("Id")
    private String id;
    @JsonProperty("ExtensionArn")
    private String extensionArn;
    @JsonProperty("ResourceArn")
    private String resourceArn;
    @JsonProperty("Arn")
    private String arn;
    @JsonProperty("Parameters")
    private Object parameters;
    @JsonProperty("ExtensionVersionNumber")
    private Integer extensionVersionNumber;
    @JsonIgnore
    private String extensionId;
    @JsonIgnore
    private Map<String, String> tags = new HashMap<>();

    public ExtensionAssociation() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getExtensionArn() { return extensionArn; }
    public void setExtensionArn(String extensionArn) { this.extensionArn = extensionArn; }

    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Object getParameters() { return parameters; }
    public void setParameters(Object parameters) { this.parameters = parameters; }

    public Integer getExtensionVersionNumber() { return extensionVersionNumber; }
    public void setExtensionVersionNumber(Integer extensionVersionNumber) { this.extensionVersionNumber = extensionVersionNumber; }

    public String getExtensionId() { return extensionId; }
    public void setExtensionId(String extensionId) { this.extensionId = extensionId; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
}
