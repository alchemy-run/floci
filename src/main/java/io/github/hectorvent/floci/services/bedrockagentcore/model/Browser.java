package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Bedrock AgentCore custom browser. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Browser {

    private String browserId;
    private String browserArn;
    private String name;
    private String description;
    private String executionRoleArn;
    private JsonNode networkConfiguration;
    private JsonNode recording;
    private JsonNode browserSigning;
    private JsonNode enterprisePolicies;
    private JsonNode certificates;
    private String status;
    private String failureReason;
    private String createdAt;
    private String lastUpdatedAt;
    private Map<String, String> tags;

    public Browser() {
    }

    public String getBrowserId() {
        return browserId;
    }

    public void setBrowserId(String browserId) {
        this.browserId = browserId;
    }

    public String getBrowserArn() {
        return browserArn;
    }

    public void setBrowserArn(String browserArn) {
        this.browserArn = browserArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public JsonNode getNetworkConfiguration() {
        return copy(networkConfiguration);
    }

    public void setNetworkConfiguration(JsonNode networkConfiguration) {
        this.networkConfiguration = copy(networkConfiguration);
    }

    public JsonNode getRecording() {
        return copy(recording);
    }

    public void setRecording(JsonNode recording) {
        this.recording = copy(recording);
    }

    public JsonNode getBrowserSigning() {
        return copy(browserSigning);
    }

    public void setBrowserSigning(JsonNode browserSigning) {
        this.browserSigning = copy(browserSigning);
    }

    public JsonNode getEnterprisePolicies() {
        return copy(enterprisePolicies);
    }

    public void setEnterprisePolicies(JsonNode enterprisePolicies) {
        this.enterprisePolicies = copy(enterprisePolicies);
    }

    public JsonNode getCertificates() {
        return copy(certificates);
    }

    public void setCertificates(JsonNode certificates) {
        this.certificates = copy(certificates);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
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

    public Map<String, String> getTags() {
        return tags == null ? null : Map.copyOf(tags);
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
