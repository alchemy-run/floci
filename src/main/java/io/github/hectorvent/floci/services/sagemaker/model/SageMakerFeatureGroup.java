package io.github.hectorvent.floci.services.sagemaker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SageMakerFeatureGroup {

    private String featureGroupName;
    private String featureGroupArn;
    private String region;
    private String recordIdentifierFeatureName;
    private String eventTimeFeatureName;
    private String featureGroupStatus;
    private String roleArn;
    private String description;
    private long creationTime;
    private List<Map<String, Object>> featureDefinitions = new ArrayList<>();
    private Map<String, Object> onlineStoreConfig;
    private Map<String, Object> offlineStoreConfig;
    private Map<String, Object> throughputConfig;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();

    public SageMakerFeatureGroup() {
    }

    public String getFeatureGroupName() {
        return featureGroupName;
    }

    public void setFeatureGroupName(String featureGroupName) {
        this.featureGroupName = featureGroupName;
    }

    public String getFeatureGroupArn() {
        return featureGroupArn;
    }

    public void setFeatureGroupArn(String featureGroupArn) {
        this.featureGroupArn = featureGroupArn;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getRecordIdentifierFeatureName() {
        return recordIdentifierFeatureName;
    }

    public void setRecordIdentifierFeatureName(String recordIdentifierFeatureName) {
        this.recordIdentifierFeatureName = recordIdentifierFeatureName;
    }

    public String getEventTimeFeatureName() {
        return eventTimeFeatureName;
    }

    public void setEventTimeFeatureName(String eventTimeFeatureName) {
        this.eventTimeFeatureName = eventTimeFeatureName;
    }

    public String getFeatureGroupStatus() {
        return featureGroupStatus;
    }

    public void setFeatureGroupStatus(String featureGroupStatus) {
        this.featureGroupStatus = featureGroupStatus;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public List<Map<String, Object>> getFeatureDefinitions() {
        return featureDefinitions;
    }

    public void setFeatureDefinitions(List<Map<String, Object>> featureDefinitions) {
        this.featureDefinitions = featureDefinitions != null ? featureDefinitions : new ArrayList<>();
    }

    public Map<String, Object> getOnlineStoreConfig() {
        return onlineStoreConfig;
    }

    public void setOnlineStoreConfig(Map<String, Object> onlineStoreConfig) {
        this.onlineStoreConfig = onlineStoreConfig;
    }

    public Map<String, Object> getOfflineStoreConfig() {
        return offlineStoreConfig;
    }

    public void setOfflineStoreConfig(Map<String, Object> offlineStoreConfig) {
        this.offlineStoreConfig = offlineStoreConfig;
    }

    public Map<String, Object> getThroughputConfig() {
        return throughputConfig;
    }

    public void setThroughputConfig(Map<String, Object> throughputConfig) {
        this.throughputConfig = throughputConfig;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public Map<String, List<Map<String, Object>>> getRecords() {
        return records;
    }

    public void setRecords(Map<String, List<Map<String, Object>>> records) {
        this.records = records != null ? records : new LinkedHashMap<>();
    }
}
