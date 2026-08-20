package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigurationProfile {
    @JsonProperty("Id")
    private String id;
    @JsonProperty("ApplicationId")
    private String applicationId;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("LocationUri")
    private String locationUri;
    @JsonProperty("Type")
    private String type; // AWS.AppConfig.FeatureFlags, AWS.Freeform
    @JsonProperty("RetrievalRoleArn")
    private String retrievalRoleArn;
    @JsonProperty("Validators")
    private List<Map<String, Object>> validators;
    @JsonProperty("KmsKeyIdentifier")
    private String kmsKeyIdentifier;
    @JsonIgnore
    private Map<String, String> tags = new HashMap<>();

    public ConfigurationProfile() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLocationUri() { return locationUri; }
    public void setLocationUri(String locationUri) { this.locationUri = locationUri; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRetrievalRoleArn() { return retrievalRoleArn; }
    public void setRetrievalRoleArn(String retrievalRoleArn) { this.retrievalRoleArn = retrievalRoleArn; }

    public List<Map<String, Object>> getValidators() { return validators; }
    public void setValidators(List<Map<String, Object>> validators) { this.validators = validators; }

    public String getKmsKeyIdentifier() { return kmsKeyIdentifier; }
    public void setKmsKeyIdentifier(String kmsKeyIdentifier) { this.kmsKeyIdentifier = kmsKeyIdentifier; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags != null ? tags : new HashMap<>(); }
}
