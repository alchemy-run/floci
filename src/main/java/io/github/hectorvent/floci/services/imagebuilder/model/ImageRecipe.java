package io.github.hectorvent.floci.services.imagebuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An EC2 Image Builder image recipe version. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageRecipe {

    private String arn;
    private String name;
    private String version;
    private String description;
    private String type = "AMI";
    private String platform;
    private String parentImage;
    private String owner;
    private String workingDirectory;
    private String dateCreated;
    private String clientToken;
    private JsonNode components;
    private JsonNode blockDeviceMappings;
    private JsonNode additionalInstanceConfiguration;
    private JsonNode amiTags;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ImageRecipe() {
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getParentImage() {
        return parentImage;
    }

    public void setParentImage(String parentImage) {
        this.parentImage = parentImage;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(String dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public JsonNode getComponents() {
        return components;
    }

    public void setComponents(JsonNode components) {
        this.components = components;
    }

    public JsonNode getBlockDeviceMappings() {
        return blockDeviceMappings;
    }

    public void setBlockDeviceMappings(JsonNode blockDeviceMappings) {
        this.blockDeviceMappings = blockDeviceMappings;
    }

    public JsonNode getAdditionalInstanceConfiguration() {
        return additionalInstanceConfiguration;
    }

    public void setAdditionalInstanceConfiguration(JsonNode additionalInstanceConfiguration) {
        this.additionalInstanceConfiguration = additionalInstanceConfiguration;
    }

    public JsonNode getAmiTags() {
        return amiTags;
    }

    public void setAmiTags(JsonNode amiTags) {
        this.amiTags = amiTags;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
