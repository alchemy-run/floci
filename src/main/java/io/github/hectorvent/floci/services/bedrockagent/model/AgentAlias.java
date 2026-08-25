package io.github.hectorvent.floci.services.bedrockagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Bedrock agent alias. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentAlias {

    private String agentId;
    private String agentAliasId;
    private String agentAliasName;
    private String agentAliasArn;
    private String clientToken;
    private String description;
    private JsonNode routingConfiguration;
    private String createdAt;
    private String updatedAt;
    private String agentAliasStatus;
    private String aliasInvocationState;
    private Map<String, String> tags = new LinkedHashMap<>();

    public AgentAlias() {
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentAliasId() {
        return agentAliasId;
    }

    public void setAgentAliasId(String agentAliasId) {
        this.agentAliasId = agentAliasId;
    }

    public String getAgentAliasName() {
        return agentAliasName;
    }

    public void setAgentAliasName(String agentAliasName) {
        this.agentAliasName = agentAliasName;
    }

    public String getAgentAliasArn() {
        return agentAliasArn;
    }

    public void setAgentAliasArn(String agentAliasArn) {
        this.agentAliasArn = agentAliasArn;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getRoutingConfiguration() {
        return routingConfiguration;
    }

    public void setRoutingConfiguration(JsonNode routingConfiguration) {
        this.routingConfiguration = routingConfiguration;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getAgentAliasStatus() {
        return agentAliasStatus;
    }

    public void setAgentAliasStatus(String agentAliasStatus) {
        this.agentAliasStatus = agentAliasStatus;
    }

    public String getAliasInvocationState() {
        return aliasInvocationState;
    }

    public void setAliasInvocationState(String aliasInvocationState) {
        this.aliasInvocationState = aliasInvocationState;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
