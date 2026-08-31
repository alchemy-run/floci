package io.github.hectorvent.floci.services.dlm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Data Lifecycle Manager policy. Wire names are PascalCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class LifecyclePolicy {

    private String policyId;
    private String description;
    private String state;
    private String statusMessage;
    private String executionRoleArn;
    private String dateCreated;
    private String dateModified;
    private JsonNode policyDetails;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String policyArn;
    private Boolean defaultPolicy;

    public LifecyclePolicy() {
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public String getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(String dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getDateModified() {
        return dateModified;
    }

    public void setDateModified(String dateModified) {
        this.dateModified = dateModified;
    }

    public JsonNode getPolicyDetails() {
        return policyDetails == null ? null : policyDetails.deepCopy();
    }

    public void setPolicyDetails(JsonNode policyDetails) {
        this.policyDetails = policyDetails == null ? null : policyDetails.deepCopy();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public String getPolicyArn() {
        return policyArn;
    }

    public void setPolicyArn(String policyArn) {
        this.policyArn = policyArn;
    }

    public Boolean getDefaultPolicy() {
        return defaultPolicy;
    }

    public void setDefaultPolicy(Boolean defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }
}
