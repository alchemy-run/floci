package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon EMR Serverless application. Wire names are camelCase. */
@RegisterForReflection
public class Application {

    private String applicationId;
    private String name;
    private String arn;
    private String releaseLabel;
    private String type;
    private String state;
    private String stateDetails;
    private String architecture;
    private long createdAt;
    private long updatedAt;
    private Boolean autoStartEnabled;
    private Boolean autoStopEnabled;
    private Integer idleTimeoutMinutes;
    private Map<String, Object> initialCapacity;
    private Map<String, Object> maximumCapacity;
    private Map<String, Object> networkConfiguration;
    private Map<String, Object> interactiveConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String region;
    private String clientToken;

    public Application() {
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getReleaseLabel() {
        return releaseLabel;
    }

    public void setReleaseLabel(String releaseLabel) {
        this.releaseLabel = releaseLabel;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateDetails() {
        return stateDetails;
    }

    public void setStateDetails(String stateDetails) {
        this.stateDetails = stateDetails;
    }

    public String getArchitecture() {
        return architecture;
    }

    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getAutoStartEnabled() {
        return autoStartEnabled;
    }

    public void setAutoStartEnabled(Boolean autoStartEnabled) {
        this.autoStartEnabled = autoStartEnabled;
    }

    public Boolean getAutoStopEnabled() {
        return autoStopEnabled;
    }

    public void setAutoStopEnabled(Boolean autoStopEnabled) {
        this.autoStopEnabled = autoStopEnabled;
    }

    public Integer getIdleTimeoutMinutes() {
        return idleTimeoutMinutes;
    }

    public void setIdleTimeoutMinutes(Integer idleTimeoutMinutes) {
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    public Map<String, Object> getInitialCapacity() {
        return initialCapacity;
    }

    public void setInitialCapacity(Map<String, Object> initialCapacity) {
        this.initialCapacity = initialCapacity;
    }

    public Map<String, Object> getMaximumCapacity() {
        return maximumCapacity;
    }

    public void setMaximumCapacity(Map<String, Object> maximumCapacity) {
        this.maximumCapacity = maximumCapacity;
    }

    public Map<String, Object> getNetworkConfiguration() {
        return networkConfiguration;
    }

    public void setNetworkConfiguration(Map<String, Object> networkConfiguration) {
        this.networkConfiguration = networkConfiguration;
    }

    public Map<String, Object> getInteractiveConfiguration() {
        return interactiveConfiguration;
    }

    public void setInteractiveConfiguration(Map<String, Object> interactiveConfiguration) {
        this.interactiveConfiguration = interactiveConfiguration;
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

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }
}
