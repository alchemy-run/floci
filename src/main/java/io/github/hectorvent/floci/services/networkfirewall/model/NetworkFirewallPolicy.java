package io.github.hectorvent.floci.services.networkfirewall.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkFirewallPolicy {

    private String name;
    private String arn;
    private String id;
    private String region;
    private String description;
    private String status = "ACTIVE";
    private String updateToken;
    private int numberOfAssociations;
    private long lastModifiedTime;
    private Map<String, Object> firewallPolicy = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public NetworkFirewallPolicy() {
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getUpdateToken() {
        return updateToken;
    }

    public void setUpdateToken(String updateToken) {
        this.updateToken = updateToken;
    }

    public int getNumberOfAssociations() {
        return numberOfAssociations;
    }

    public void setNumberOfAssociations(int numberOfAssociations) {
        this.numberOfAssociations = numberOfAssociations;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public Map<String, Object> getFirewallPolicy() {
        return firewallPolicy;
    }

    public void setFirewallPolicy(Map<String, Object> firewallPolicy) {
        this.firewallPolicy = firewallPolicy != null ? firewallPolicy : new LinkedHashMap<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
