package io.github.hectorvent.floci.services.internetmonitor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A CloudWatch Internet Monitor monitor. Wire names are PascalCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class Monitor {

    private String monitorName;
    private String monitorArn;
    private List<String> resources = new ArrayList<>();
    private String status;
    private String createdAt;
    private String modifiedAt;
    private String processingStatus;
    private String processingStatusInfo;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Integer maxCityNetworksToMonitor;
    private Integer trafficPercentageToMonitor;
    private JsonNode internetMeasurementsLogDelivery;
    private JsonNode healthEventsConfig;

    public Monitor() {
    }

    public String getMonitorName() {
        return monitorName;
    }

    public void setMonitorName(String monitorName) {
        this.monitorName = monitorName;
    }

    public String getMonitorArn() {
        return monitorArn;
    }

    public void setMonitorArn(String monitorArn) {
        this.monitorArn = monitorArn;
    }

    public List<String> getResources() {
        return resources;
    }

    public void setResources(List<String> resources) {
        this.resources = resources == null ? new ArrayList<>() : new ArrayList<>(resources);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(String modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getProcessingStatusInfo() {
        return processingStatusInfo;
    }

    public void setProcessingStatusInfo(String processingStatusInfo) {
        this.processingStatusInfo = processingStatusInfo;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public Integer getMaxCityNetworksToMonitor() {
        return maxCityNetworksToMonitor;
    }

    public void setMaxCityNetworksToMonitor(Integer maxCityNetworksToMonitor) {
        this.maxCityNetworksToMonitor = maxCityNetworksToMonitor;
    }

    public Integer getTrafficPercentageToMonitor() {
        return trafficPercentageToMonitor;
    }

    public void setTrafficPercentageToMonitor(Integer trafficPercentageToMonitor) {
        this.trafficPercentageToMonitor = trafficPercentageToMonitor;
    }

    public JsonNode getInternetMeasurementsLogDelivery() {
        return internetMeasurementsLogDelivery == null ? null : internetMeasurementsLogDelivery.deepCopy();
    }

    public void setInternetMeasurementsLogDelivery(JsonNode internetMeasurementsLogDelivery) {
        this.internetMeasurementsLogDelivery = internetMeasurementsLogDelivery == null
                ? null
                : internetMeasurementsLogDelivery.deepCopy();
    }

    public JsonNode getHealthEventsConfig() {
        return healthEventsConfig == null ? null : healthEventsConfig.deepCopy();
    }

    public void setHealthEventsConfig(JsonNode healthEventsConfig) {
        this.healthEventsConfig = healthEventsConfig == null ? null : healthEventsConfig.deepCopy();
    }
}
