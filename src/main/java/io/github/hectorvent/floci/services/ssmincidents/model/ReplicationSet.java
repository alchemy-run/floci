package io.github.hectorvent.floci.services.ssmincidents.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Account-singleton Incident Manager replication set. */
@RegisterForReflection
public class ReplicationSet {

    private String arn;
    private Map<String, RegionInfo> regionMap = new LinkedHashMap<>();
    private String status;
    private boolean deletionProtected;
    private long createdTime;
    private String createdBy;
    private long lastModifiedTime;
    private String lastModifiedBy;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ReplicationSet() {
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public Map<String, RegionInfo> getRegionMap() {
        return regionMap;
    }

    public void setRegionMap(Map<String, RegionInfo> regionMap) {
        this.regionMap = regionMap == null ? new LinkedHashMap<>() : new LinkedHashMap<>(regionMap);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isDeletionProtected() {
        return deletionProtected;
    }

    public void setDeletionProtected(boolean deletionProtected) {
        this.deletionProtected = deletionProtected;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
