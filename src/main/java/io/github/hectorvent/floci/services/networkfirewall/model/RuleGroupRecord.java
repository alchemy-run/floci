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
public class RuleGroupRecord {

    private String ruleGroupArn;
    private String ruleGroupName;
    private String ruleGroupId;
    private String type;
    private int capacity;
    private String description;
    private String region;
    private String status = "ACTIVE";
    private String updateToken;
    private long lastModifiedTime;
    private int numberOfAssociations;
    private JsonNode definition;
    private JsonNode summaryConfiguration;
    private JsonNode encryptionConfiguration;
    private Map<String, String> tags = new LinkedHashMap<>();

    public RuleGroupRecord() {
    }

    public String getRuleGroupArn() {
        return ruleGroupArn;
    }

    public void setRuleGroupArn(String ruleGroupArn) {
        this.ruleGroupArn = ruleGroupArn;
    }

    public String getRuleGroupName() {
        return ruleGroupName;
    }

    public void setRuleGroupName(String ruleGroupName) {
        this.ruleGroupName = ruleGroupName;
    }

    public String getRuleGroupId() {
        return ruleGroupId;
    }

    public void setRuleGroupId(String ruleGroupId) {
        this.ruleGroupId = ruleGroupId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
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

    public JsonNode getSummaryConfiguration() {
        return summaryConfiguration;
    }

    public void setSummaryConfiguration(JsonNode summaryConfiguration) {
        this.summaryConfiguration = summaryConfiguration;
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
