package io.github.hectorvent.floci.services.finspace.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A kdb dataview of a database. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KxDataview {

    private String environmentId;
    private String databaseName;
    private String dataviewName;
    private String azMode;
    private String availabilityZoneId;
    private String changesetId;
    private String description;
    private boolean autoUpdate;
    private boolean readWrite;
    private String status;
    private String region;
    private long createdTimestamp;
    private long lastModifiedTimestamp;
    private JsonNode segmentConfigurations;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientToken;

    public KxDataview() {
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getDataviewName() {
        return dataviewName;
    }

    public void setDataviewName(String dataviewName) {
        this.dataviewName = dataviewName;
    }

    public String getAzMode() {
        return azMode;
    }

    public void setAzMode(String azMode) {
        this.azMode = azMode;
    }

    public String getAvailabilityZoneId() {
        return availabilityZoneId;
    }

    public void setAvailabilityZoneId(String availabilityZoneId) {
        this.availabilityZoneId = availabilityZoneId;
    }

    public String getChangesetId() {
        return changesetId;
    }

    public void setChangesetId(String changesetId) {
        this.changesetId = changesetId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isAutoUpdate() {
        return autoUpdate;
    }

    public void setAutoUpdate(boolean autoUpdate) {
        this.autoUpdate = autoUpdate;
    }

    public boolean isReadWrite() {
        return readWrite;
    }

    public void setReadWrite(boolean readWrite) {
        this.readWrite = readWrite;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(long createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public long getLastModifiedTimestamp() {
        return lastModifiedTimestamp;
    }

    public void setLastModifiedTimestamp(long lastModifiedTimestamp) {
        this.lastModifiedTimestamp = lastModifiedTimestamp;
    }

    public JsonNode getSegmentConfigurations() {
        return segmentConfigurations;
    }

    public void setSegmentConfigurations(JsonNode segmentConfigurations) {
        this.segmentConfigurations = segmentConfigurations;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }
}
