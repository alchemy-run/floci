package io.github.hectorvent.floci.services.shield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shield Advanced protection for a single resource.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShieldProtection {

    private String id;
    private String name;
    private String resourceArn;
    private String protectionArn;
    private List<String> healthCheckIds = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public ShieldProtection() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getProtectionArn() {
        return protectionArn;
    }

    public void setProtectionArn(String protectionArn) {
        this.protectionArn = protectionArn;
    }

    public List<String> getHealthCheckIds() {
        return healthCheckIds;
    }

    public void setHealthCheckIds(List<String> healthCheckIds) {
        this.healthCheckIds = healthCheckIds != null ? healthCheckIds : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
