package io.github.hectorvent.floci.services.networkfirewall.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FirewallPolicyRecord {

    private String firewallPolicyArn;
    private String firewallPolicyName;
    private String firewallPolicyId;
    private String description;
    private String region;
    private String status = "ACTIVE";
    private String updateToken;
    private long lastModifiedTime;
    private int numberOfAssociations;
    private JsonNode definition;
    private JsonNode encryptionConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();

    public FirewallPolicyRecord() {
    }

    public String getFirewallPolicyArn() {
        return firewallPolicyArn;
    }

    public void setFirewallPolicyArn(String firewallPolicyArn) {
        this.firewallPolicyArn = firewallPolicyArn;
    }

    public String getFirewallPolicyName() {
        return firewallPolicyName;
    }

    public void setFirewallPolicyName(String firewallPolicyName) {
        this.firewallPolicyName = firewallPolicyName;
    }

    public String getFirewallPolicyId() {
        return firewallPolicyId;
    }

    public void setFirewallPolicyId(String firewallPolicyId) {
        this.firewallPolicyId = firewallPolicyId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
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

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public int getNumberOfAssociations() {
        return numberOfAssociations;
    }

    public void setNumberOfAssociations(int numberOfAssociations) {
        this.numberOfAssociations = numberOfAssociations;
    }

    public JsonNode getDefinition() {
        return definition;
    }

    public void setDefinition(JsonNode definition) {
        this.definition = definition;
    }

    public JsonNode getEncryptionConfiguration() {
        return encryptionConfiguration;
    }

    public void setEncryptionConfiguration(JsonNode encryptionConfiguration) {
        this.encryptionConfiguration = encryptionConfiguration;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
