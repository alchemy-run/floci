package io.github.hectorvent.floci.services.mediapackagev2.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An AWS Elemental MediaPackage v2 harvest job. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarvestJob {

    private String channelGroupName;
    private String channelName;
    private String originEndpointName;
    private String harvestJobName;
    private String arn;
    private String description;
    private String region;
    private String etag;
    private String status;
    private String errorMessage;
    private long createdAt;
    private long modifiedAt;
    private JsonNode destination;
    private JsonNode harvestedManifests;
    private JsonNode scheduleConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();

    public HarvestJob() {
    }

    public String getChannelGroupName() {
        return channelGroupName;
    }

    public void setChannelGroupName(String channelGroupName) {
        this.channelGroupName = channelGroupName;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getOriginEndpointName() {
        return originEndpointName;
    }

    public void setOriginEndpointName(String originEndpointName) {
        this.originEndpointName = originEndpointName;
    }

    public String getHarvestJobName() {
        return harvestJobName;
    }

    public void setHarvestJobName(String harvestJobName) {
        this.harvestJobName = harvestJobName;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getETag() {
        return etag;
    }

    public void setETag(String etag) {
        this.etag = etag;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(long modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public JsonNode getDestination() {
        return destination;
    }

    public void setDestination(JsonNode destination) {
        this.destination = destination;
    }

    public JsonNode getHarvestedManifests() {
        return harvestedManifests;
    }

    public void setHarvestedManifests(JsonNode harvestedManifests) {
        this.harvestedManifests = harvestedManifests;
    }

    public JsonNode getScheduleConfiguration() {
        return scheduleConfiguration;
    }

    public void setScheduleConfiguration(JsonNode scheduleConfiguration) {
        this.scheduleConfiguration = scheduleConfiguration;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
