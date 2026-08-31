package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AWS RAM customer managed permission. */
@RegisterForReflection
public class RamPermission {
    private String id;
    private String arn;
    private String name;
    private String resourceType;
    private String permissionType;
    private String featureSet;
    private int defaultVersion;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<RamPermissionVersion> versions = new ArrayList<>();
    private long creationTime;
    private long lastUpdatedTime;

    public RamPermission() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getPermissionType() {
        return permissionType;
    }

    public void setPermissionType(String permissionType) {
        this.permissionType = permissionType;
    }

    public String getFeatureSet() {
        return featureSet;
    }

    public void setFeatureSet(String featureSet) {
        this.featureSet = featureSet;
    }

    public int getDefaultVersion() {
        return defaultVersion;
    }

    public void setDefaultVersion(int defaultVersion) {
        this.defaultVersion = defaultVersion;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public List<RamPermissionVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<RamPermissionVersion> versions) {
        this.versions = versions == null ? new ArrayList<>() : new ArrayList<>(versions);
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }

    /** Default version number as RAM's wire {@code version} string. */
    public String getVersion() {
        return String.valueOf(defaultVersion);
    }

    /** Summaries always describe the default version. */
    public boolean isDefaultVersion() {
        return true;
    }

    public String getStatus() {
        for (RamPermissionVersion version : versions) {
            if (version.getVersion() == defaultVersion && version.getStatus() != null) {
                return version.getStatus();
            }
        }
        return "ATTACHABLE";
    }

    public boolean isResourceTypeDefault() {
        return false;
    }

    public String getPolicy() {
        for (RamPermissionVersion version : versions) {
            if (version.getVersion() == defaultVersion) {
                return version.getPolicyTemplate();
            }
        }
        return null;
    }
}
