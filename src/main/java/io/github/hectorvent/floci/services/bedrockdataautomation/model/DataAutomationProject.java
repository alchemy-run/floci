package io.github.hectorvent.floci.services.bedrockdataautomation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Bedrock Data Automation project. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataAutomationProject {

    private String projectArn;
    private String projectName;
    private String projectDescription;
    private String projectStage = "LIVE";
    private String projectType = "ASYNC";
    private String status = "COMPLETED";
    private String creationTime;
    private String lastModifiedTime;
    private JsonNode standardOutputConfiguration;
    private JsonNode customOutputConfiguration;
    private JsonNode overrideConfiguration;
    private JsonNode dataAutomationLibraryConfiguration;
    private String kmsKeyId;
    private Map<String, String> kmsEncryptionContext;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String region;

    public DataAutomationProject() {
    }

    public String getProjectArn() {
        return projectArn;
    }

    public void setProjectArn(String projectArn) {
        this.projectArn = projectArn;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectDescription() {
        return projectDescription;
    }

    public void setProjectDescription(String projectDescription) {
        this.projectDescription = projectDescription;
    }

    public String getProjectStage() {
        return projectStage;
    }

    public void setProjectStage(String projectStage) {
        this.projectStage = projectStage;
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(String lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public JsonNode getStandardOutputConfiguration() {
        return standardOutputConfiguration;
    }

    public void setStandardOutputConfiguration(JsonNode standardOutputConfiguration) {
        this.standardOutputConfiguration = standardOutputConfiguration;
    }

    public JsonNode getCustomOutputConfiguration() {
        return customOutputConfiguration;
    }

    public void setCustomOutputConfiguration(JsonNode customOutputConfiguration) {
        this.customOutputConfiguration = customOutputConfiguration;
    }

    public JsonNode getOverrideConfiguration() {
        return overrideConfiguration;
    }

    public void setOverrideConfiguration(JsonNode overrideConfiguration) {
        this.overrideConfiguration = overrideConfiguration;
    }

    public JsonNode getDataAutomationLibraryConfiguration() {
        return dataAutomationLibraryConfiguration;
    }

    public void setDataAutomationLibraryConfiguration(JsonNode dataAutomationLibraryConfiguration) {
        this.dataAutomationLibraryConfiguration = dataAutomationLibraryConfiguration;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public Map<String, String> getKmsEncryptionContext() {
        return kmsEncryptionContext;
    }

    public void setKmsEncryptionContext(Map<String, String> kmsEncryptionContext) {
        this.kmsEncryptionContext = kmsEncryptionContext;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
