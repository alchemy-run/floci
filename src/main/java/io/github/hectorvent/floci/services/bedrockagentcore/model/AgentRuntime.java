package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Bedrock AgentCore agent runtime. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentRuntime {

    private String agentRuntimeId;
    private String agentRuntimeArn;
    private String agentRuntimeName;
    private String agentRuntimeVersion;
    private String description;
    private String roleArn;
    private JsonNode agentRuntimeArtifact;
    private JsonNode networkConfiguration;
    private JsonNode protocolConfiguration;
    private JsonNode authorizerConfiguration;
    private JsonNode requestHeaderConfiguration;
    private JsonNode lifecycleConfiguration;
    private JsonNode environmentVariables;
    private JsonNode metadataConfiguration;
    private JsonNode filesystemConfigurations;
    private JsonNode workloadIdentityDetails;
    private String status;
    private String failureReason;
    private String createdAt;
    private String lastUpdatedAt;
    private Map<String, String> tags;

    public AgentRuntime() {
    }

    public String getAgentRuntimeId() {
        return agentRuntimeId;
    }

    public void setAgentRuntimeId(String agentRuntimeId) {
        this.agentRuntimeId = agentRuntimeId;
    }

    public String getAgentRuntimeArn() {
        return agentRuntimeArn;
    }

    public void setAgentRuntimeArn(String agentRuntimeArn) {
        this.agentRuntimeArn = agentRuntimeArn;
    }

    public String getAgentRuntimeName() {
        return agentRuntimeName;
    }

    public void setAgentRuntimeName(String agentRuntimeName) {
        this.agentRuntimeName = agentRuntimeName;
    }

    public String getAgentRuntimeVersion() {
        return agentRuntimeVersion;
    }

    public void setAgentRuntimeVersion(String agentRuntimeVersion) {
        this.agentRuntimeVersion = agentRuntimeVersion;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public JsonNode getAgentRuntimeArtifact() {
        return copy(agentRuntimeArtifact);
    }

    public void setAgentRuntimeArtifact(JsonNode agentRuntimeArtifact) {
        this.agentRuntimeArtifact = copy(agentRuntimeArtifact);
    }

    public JsonNode getNetworkConfiguration() {
        return copy(networkConfiguration);
    }

    public void setNetworkConfiguration(JsonNode networkConfiguration) {
        this.networkConfiguration = copy(networkConfiguration);
    }

    public JsonNode getProtocolConfiguration() {
        return copy(protocolConfiguration);
    }

    public void setProtocolConfiguration(JsonNode protocolConfiguration) {
        this.protocolConfiguration = copy(protocolConfiguration);
    }

    public JsonNode getAuthorizerConfiguration() {
        return copy(authorizerConfiguration);
    }

    public void setAuthorizerConfiguration(JsonNode authorizerConfiguration) {
        this.authorizerConfiguration = copy(authorizerConfiguration);
    }

    public JsonNode getRequestHeaderConfiguration() {
        return copy(requestHeaderConfiguration);
    }

    public void setRequestHeaderConfiguration(JsonNode requestHeaderConfiguration) {
        this.requestHeaderConfiguration = copy(requestHeaderConfiguration);
    }

    public JsonNode getLifecycleConfiguration() {
        return copy(lifecycleConfiguration);
    }

    public void setLifecycleConfiguration(JsonNode lifecycleConfiguration) {
        this.lifecycleConfiguration = copy(lifecycleConfiguration);
    }

    public JsonNode getEnvironmentVariables() {
        return copy(environmentVariables);
    }

    public void setEnvironmentVariables(JsonNode environmentVariables) {
        this.environmentVariables = copy(environmentVariables);
    }

    public JsonNode getMetadataConfiguration() {
        return copy(metadataConfiguration);
    }

    public void setMetadataConfiguration(JsonNode metadataConfiguration) {
        this.metadataConfiguration = copy(metadataConfiguration);
    }

    public JsonNode getFilesystemConfigurations() {
        return copy(filesystemConfigurations);
    }

    public void setFilesystemConfigurations(JsonNode filesystemConfigurations) {
        this.filesystemConfigurations = copy(filesystemConfigurations);
    }

    public JsonNode getWorkloadIdentityDetails() {
        return copy(workloadIdentityDetails);
    }

    public void setWorkloadIdentityDetails(JsonNode workloadIdentityDetails) {
        this.workloadIdentityDetails = copy(workloadIdentityDetails);
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
