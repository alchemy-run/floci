package io.github.hectorvent.floci.services.databrew.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Job {

    private String name;
    private String resourceArn;
    private String accountId;
    private String region;
    private String type;
    private String datasetName;
    private String projectName;
    private String roleArn;
    private JsonNode outputLocation;
    private JsonNode outputs;
    private JsonNode recipeReference;
    private JsonNode jobSample;
    private JsonNode profileConfiguration;
    private JsonNode validationConfigurations;
    private String encryptionKeyArn;
    private String encryptionMode;
    private String logSubscription;
    private Integer maxCapacity;
    private Integer maxRetries;
    private Integer timeout;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<JobRun> runs = new ArrayList<>();
    private long createDate;
    private long lastModifiedDate;

    public Job() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDatasetName() {
        return datasetName;
    }

    public void setDatasetName(String datasetName) {
        this.datasetName = datasetName;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public JsonNode getOutputLocation() {
        return outputLocation;
    }

    public void setOutputLocation(JsonNode outputLocation) {
        this.outputLocation = outputLocation;
    }

    public JsonNode getOutputs() {
        return outputs;
    }

    public void setOutputs(JsonNode outputs) {
        this.outputs = outputs;
    }

    public JsonNode getRecipeReference() {
        return recipeReference;
    }

    public void setRecipeReference(JsonNode recipeReference) {
        this.recipeReference = recipeReference;
    }

    public JsonNode getJobSample() {
        return jobSample;
    }

    public void setJobSample(JsonNode jobSample) {
        this.jobSample = jobSample;
    }

    public JsonNode getProfileConfiguration() {
        return profileConfiguration;
    }

    public void setProfileConfiguration(JsonNode profileConfiguration) {
        this.profileConfiguration = profileConfiguration;
    }

    public JsonNode getValidationConfigurations() {
        return validationConfigurations;
    }

    public void setValidationConfigurations(JsonNode validationConfigurations) {
        this.validationConfigurations = validationConfigurations;
    }

    public String getEncryptionKeyArn() {
        return encryptionKeyArn;
    }

    public void setEncryptionKeyArn(String encryptionKeyArn) {
        this.encryptionKeyArn = encryptionKeyArn;
    }

    public String getEncryptionMode() {
        return encryptionMode;
    }

    public void setEncryptionMode(String encryptionMode) {
        this.encryptionMode = encryptionMode;
    }

    public String getLogSubscription() {
        return logSubscription;
    }

    public void setLogSubscription(String logSubscription) {
        this.logSubscription = logSubscription;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(Integer maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public List<JobRun> getRuns() {
        if (runs == null) {
            runs = new ArrayList<>();
        }
        return runs;
    }

    public void setRuns(List<JobRun> runs) {
        this.runs = runs != null ? runs : new ArrayList<>();
    }

    public long getCreateDate() {
        return createDate;
    }

    public void setCreateDate(long createDate) {
        this.createDate = createDate;
    }

    public long getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(long lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }
}
