package io.github.hectorvent.floci.services.imagebuilder.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An EC2 Image Builder image pipeline. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImagePipeline {

    private String arn;
    private String name;
    private String description;
    private String platform;
    private boolean enhancedImageMetadataEnabled = true;
    private String imageRecipeArn;
    private String containerRecipeArn;
    private String infrastructureConfigurationArn;
    private String distributionConfigurationArn;
    private String status = "ENABLED";
    private String dateCreated;
    private String dateUpdated;
    private String dateLastRun;
    private String lastRunStatus;
    private String executionRole;
    private String clientToken;
    private JsonNode imageTestsConfiguration;
    private JsonNode schedule;
    private JsonNode imageScanningConfiguration;
    private JsonNode imageTags;
    private JsonNode workflows;
    private JsonNode loggingConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ImagePipeline() {
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

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public boolean isEnhancedImageMetadataEnabled() {
        return enhancedImageMetadataEnabled;
    }

    public void setEnhancedImageMetadataEnabled(boolean enhancedImageMetadataEnabled) {
        this.enhancedImageMetadataEnabled = enhancedImageMetadataEnabled;
    }

    public String getImageRecipeArn() {
        return imageRecipeArn;
    }

    public void setImageRecipeArn(String imageRecipeArn) {
        this.imageRecipeArn = imageRecipeArn;
    }

    public String getContainerRecipeArn() {
        return containerRecipeArn;
    }

    public void setContainerRecipeArn(String containerRecipeArn) {
        this.containerRecipeArn = containerRecipeArn;
    }

    public String getInfrastructureConfigurationArn() {
        return infrastructureConfigurationArn;
    }

    public void setInfrastructureConfigurationArn(String infrastructureConfigurationArn) {
        this.infrastructureConfigurationArn = infrastructureConfigurationArn;
    }

    public String getDistributionConfigurationArn() {
        return distributionConfigurationArn;
    }

    public void setDistributionConfigurationArn(String distributionConfigurationArn) {
        this.distributionConfigurationArn = distributionConfigurationArn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(String dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getDateUpdated() {
        return dateUpdated;
    }

    public void setDateUpdated(String dateUpdated) {
        this.dateUpdated = dateUpdated;
    }

    public String getDateLastRun() {
        return dateLastRun;
    }

    public void setDateLastRun(String dateLastRun) {
        this.dateLastRun = dateLastRun;
    }

    public String getLastRunStatus() {
        return lastRunStatus;
    }

    public void setLastRunStatus(String lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }

    public String getExecutionRole() {
        return executionRole;
    }

    public void setExecutionRole(String executionRole) {
        this.executionRole = executionRole;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public JsonNode getImageTestsConfiguration() {
        return imageTestsConfiguration;
    }

    public void setImageTestsConfiguration(JsonNode imageTestsConfiguration) {
        this.imageTestsConfiguration = imageTestsConfiguration;
    }

    public JsonNode getSchedule() {
        return schedule;
    }

    public void setSchedule(JsonNode schedule) {
        this.schedule = schedule;
    }

    public JsonNode getImageScanningConfiguration() {
        return imageScanningConfiguration;
    }

    public void setImageScanningConfiguration(JsonNode imageScanningConfiguration) {
        this.imageScanningConfiguration = imageScanningConfiguration;
    }

    public JsonNode getImageTags() {
        return imageTags;
    }

    public void setImageTags(JsonNode imageTags) {
        this.imageTags = imageTags;
    }

    public JsonNode getWorkflows() {
        return workflows;
    }

    public void setWorkflows(JsonNode workflows) {
        this.workflows = workflows;
    }

    public JsonNode getLoggingConfiguration() {
        return loggingConfiguration;
    }

    public void setLoggingConfiguration(JsonNode loggingConfiguration) {
        this.loggingConfiguration = loggingConfiguration;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
