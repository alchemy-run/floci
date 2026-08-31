package io.github.hectorvent.floci.services.appintegrations.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An Amazon AppIntegrations data integration. Wire JSON is PascalCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DataIntegration {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String kmsKey;
    private String sourceURI;
    private String clientToken;
    private JsonNode scheduleConfiguration;
    private JsonNode fileConfiguration;
    private JsonNode objectConfiguration;
    private Map<String, String> tags;
    private List<DataIntegrationAssociation> associations;

    public DataIntegration() {
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

    public String getKmsKey() {
        return kmsKey;
    }

    public void setKmsKey(String kmsKey) {
        this.kmsKey = kmsKey;
    }

    public String getSourceURI() {
        return sourceURI;
    }

    public void setSourceURI(String sourceURI) {
        this.sourceURI = sourceURI;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public JsonNode getScheduleConfiguration() {
        return scheduleConfiguration;
    }

    public void setScheduleConfiguration(JsonNode scheduleConfiguration) {
        this.scheduleConfiguration = scheduleConfiguration;
    }

    public JsonNode getFileConfiguration() {
        return fileConfiguration;
    }

    public void setFileConfiguration(JsonNode fileConfiguration) {
        this.fileConfiguration = fileConfiguration;
    }

    public JsonNode getObjectConfiguration() {
        return objectConfiguration;
    }

    public void setObjectConfiguration(JsonNode objectConfiguration) {
        this.objectConfiguration = objectConfiguration;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    @JsonIgnore
    public List<DataIntegrationAssociation> getAssociations() {
        return associations;
    }

    @JsonIgnore
    public void setAssociations(List<DataIntegrationAssociation> associations) {
        this.associations = associations == null ? new ArrayList<>() : new ArrayList<>(associations);
    }
}
