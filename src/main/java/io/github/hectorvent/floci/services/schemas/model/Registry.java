package io.github.hectorvent.floci.services.schemas.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** EventBridge Schema Registry. */
@RegisterForReflection
public class Registry {
    private String registryName;
    private String registryArn;
    private String description;
    private String policy;
    private String policyRevisionId;
    private String lastModified;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Registry() {
    }

    public String getRegistryName() {
        return registryName;
    }

    public void setRegistryName(String registryName) {
        this.registryName = registryName;
    }

    public String getRegistryArn() {
        return registryArn;
    }

    public void setRegistryArn(String registryArn) {
        this.registryArn = registryArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public String getPolicyRevisionId() {
        return policyRevisionId;
    }

    public void setPolicyRevisionId(String policyRevisionId) {
        this.policyRevisionId = policyRevisionId;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
