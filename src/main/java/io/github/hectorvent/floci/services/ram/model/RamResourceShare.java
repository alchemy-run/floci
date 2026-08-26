package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AWS RAM resource share. */
@RegisterForReflection
public class RamResourceShare {
    private String arn;
    private String name;
    private String region;
    private String owningAccountId;
    private boolean allowExternalPrincipals = true;
    private String status = "ACTIVE";
    private String statusMessage;
    private String featureSet = "STANDARD";
    private long creationTime;
    private long lastUpdatedTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<String> permissionArns = new ArrayList<>();
    private List<RamAssociation> associations = new ArrayList<>();

    public RamResourceShare() {
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

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getOwningAccountId() {
        return owningAccountId;
    }

    public void setOwningAccountId(String owningAccountId) {
        this.owningAccountId = owningAccountId;
    }

    public boolean isAllowExternalPrincipals() {
        return allowExternalPrincipals;
    }

    public void setAllowExternalPrincipals(boolean allowExternalPrincipals) {
        this.allowExternalPrincipals = allowExternalPrincipals;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String getFeatureSet() {
        return featureSet;
    }

    public void setFeatureSet(String featureSet) {
        this.featureSet = featureSet;
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

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public List<String> getPermissionArns() {
        return permissionArns;
    }

    public void setPermissionArns(List<String> permissionArns) {
        this.permissionArns = permissionArns == null ? new ArrayList<>() : new ArrayList<>(permissionArns);
    }

    public List<RamAssociation> getAssociations() {
        return associations;
    }

    public void setAssociations(List<RamAssociation> associations) {
        this.associations = associations == null ? new ArrayList<>() : new ArrayList<>(associations);
    }
}
