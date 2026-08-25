package io.github.hectorvent.floci.services.emrcontainers.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon EMR on EKS virtual cluster. Wire names are camelCase. */
@RegisterForReflection
public class VirtualCluster {

    private String id;
    private String name;
    private String arn;
    private String state;
    private Map<String, Object> containerProvider = new LinkedHashMap<>();
    private String createdAt;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String securityConfigurationId;
    private String clientToken;
    private String region;

    public VirtualCluster() {
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

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Map<String, Object> getContainerProvider() {
        return containerProvider;
    }

    public void setContainerProvider(Map<String, Object> containerProvider) {
        this.containerProvider = containerProvider != null ? containerProvider : new LinkedHashMap<>();
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public String getSecurityConfigurationId() {
        return securityConfigurationId;
    }

    public void setSecurityConfigurationId(String securityConfigurationId) {
        this.securityConfigurationId = securityConfigurationId;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
