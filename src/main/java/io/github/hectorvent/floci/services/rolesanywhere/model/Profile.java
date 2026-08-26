package io.github.hectorvent.floci.services.rolesanywhere.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** IAM Roles Anywhere profile. */
@RegisterForReflection
public class Profile {
    private String profileId;
    private String profileArn;
    private String name;
    private boolean requireInstanceProperties;
    private boolean enabled = true;
    private String createdBy;
    private String sessionPolicy;
    private List<String> roleArns = new ArrayList<>();
    private List<String> managedPolicyArns = new ArrayList<>();
    private String createdAt;
    private String updatedAt;
    private Integer durationSeconds;
    private boolean acceptRoleSessionName;
    private List<JsonNode> attributeMappings = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public Profile() {
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getProfileArn() {
        return profileArn;
    }

    public void setProfileArn(String profileArn) {
        this.profileArn = profileArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isRequireInstanceProperties() {
        return requireInstanceProperties;
    }

    public void setRequireInstanceProperties(boolean requireInstanceProperties) {
        this.requireInstanceProperties = requireInstanceProperties;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getSessionPolicy() {
        return sessionPolicy;
    }

    public void setSessionPolicy(String sessionPolicy) {
        this.sessionPolicy = sessionPolicy;
    }

    public List<String> getRoleArns() {
        return roleArns;
    }

    public void setRoleArns(List<String> roleArns) {
        this.roleArns = roleArns == null ? new ArrayList<>() : new ArrayList<>(roleArns);
    }

    public List<String> getManagedPolicyArns() {
        return managedPolicyArns;
    }

    public void setManagedPolicyArns(List<String> managedPolicyArns) {
        this.managedPolicyArns = managedPolicyArns == null
                ? new ArrayList<>()
                : new ArrayList<>(managedPolicyArns);
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public boolean isAcceptRoleSessionName() {
        return acceptRoleSessionName;
    }

    public void setAcceptRoleSessionName(boolean acceptRoleSessionName) {
        this.acceptRoleSessionName = acceptRoleSessionName;
    }

    public List<JsonNode> getAttributeMappings() {
        return attributeMappings;
    }

    public void setAttributeMappings(List<JsonNode> attributeMappings) {
        this.attributeMappings = attributeMappings == null
                ? new ArrayList<>()
                : new ArrayList<>(attributeMappings);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
