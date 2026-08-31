package io.github.hectorvent.floci.services.vpclattice.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A VPC Lattice service network. Wire names are camelCase restJson1. */
@RegisterForReflection
public class ServiceNetwork {

    private String id;
    private String name;
    private String arn;
    private String region;
    private String authType = "NONE";
    private String createdAt;
    private String lastUpdatedAt;
    private Boolean sharingEnabled;
    private String clientToken;
    private String authPolicy;
    private String authPolicyCreatedAt;
    private String authPolicyUpdatedAt;
    private String resourcePolicy;
    private Map<String, String> tags = new LinkedHashMap<>();

    public ServiceNetwork() {
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

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public Boolean getSharingEnabled() {
        return sharingEnabled;
    }

    public void setSharingEnabled(Boolean sharingEnabled) {
        this.sharingEnabled = sharingEnabled;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getAuthPolicy() {
        return authPolicy;
    }

    public void setAuthPolicy(String authPolicy) {
        this.authPolicy = authPolicy;
    }

    public String getAuthPolicyCreatedAt() {
        return authPolicyCreatedAt;
    }

    public void setAuthPolicyCreatedAt(String authPolicyCreatedAt) {
        this.authPolicyCreatedAt = authPolicyCreatedAt;
    }

    public String getAuthPolicyUpdatedAt() {
        return authPolicyUpdatedAt;
    }

    public void setAuthPolicyUpdatedAt(String authPolicyUpdatedAt) {
        this.authPolicyUpdatedAt = authPolicyUpdatedAt;
    }

    public String getResourcePolicy() {
        return resourcePolicy;
    }

    public void setResourcePolicy(String resourcePolicy) {
        this.resourcePolicy = resourcePolicy;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
