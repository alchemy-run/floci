package io.github.hectorvent.floci.services.bedrockagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon Bedrock agent (DRAFT version). Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Agent {

    private String agentId;
    private String agentName;
    private String agentArn;
    private String agentVersion = "DRAFT";
    private String clientToken;
    private String instruction;
    private String agentStatus;
    private String foundationModel;
    private String description;
    private Integer idleSessionTTLInSeconds;
    private String agentResourceRoleArn;
    private String customerEncryptionKeyArn;
    private String createdAt;
    private String updatedAt;
    private String preparedAt;
    private JsonNode guardrailConfiguration;
    private JsonNode memoryConfiguration;
    private JsonNode promptOverrideConfiguration;
    private JsonNode customOrchestration;
    private String orchestrationType;
    private String agentCollaboration;
    private int nextVersion = 1;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Agent() {
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getAgentArn() {
        return agentArn;
    }

    public void setAgentArn(String agentArn) {
        this.agentArn = agentArn;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public String getAgentStatus() {
        return agentStatus;
    }

    public void setAgentStatus(String agentStatus) {
        this.agentStatus = agentStatus;
    }

    public String getFoundationModel() {
        return foundationModel;
    }

    public void setFoundationModel(String foundationModel) {
        this.foundationModel = foundationModel;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getIdleSessionTTLInSeconds() {
        return idleSessionTTLInSeconds;
    }

    public void setIdleSessionTTLInSeconds(Integer idleSessionTTLInSeconds) {
        this.idleSessionTTLInSeconds = idleSessionTTLInSeconds;
    }

    public String getAgentResourceRoleArn() {
        return agentResourceRoleArn;
    }

    public void setAgentResourceRoleArn(String agentResourceRoleArn) {
        this.agentResourceRoleArn = agentResourceRoleArn;
    }

    public String getCustomerEncryptionKeyArn() {
        return customerEncryptionKeyArn;
    }

    public void setCustomerEncryptionKeyArn(String customerEncryptionKeyArn) {
        this.customerEncryptionKeyArn = customerEncryptionKeyArn;
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

    public String getPreparedAt() {
        return preparedAt;
    }

    public void setPreparedAt(String preparedAt) {
        this.preparedAt = preparedAt;
    }

    public JsonNode getGuardrailConfiguration() {
        return guardrailConfiguration;
    }

    public void setGuardrailConfiguration(JsonNode guardrailConfiguration) {
        this.guardrailConfiguration = guardrailConfiguration;
    }

    public JsonNode getMemoryConfiguration() {
        return memoryConfiguration;
    }

    public void setMemoryConfiguration(JsonNode memoryConfiguration) {
        this.memoryConfiguration = memoryConfiguration;
    }

    public JsonNode getPromptOverrideConfiguration() {
        return promptOverrideConfiguration;
    }

    public void setPromptOverrideConfiguration(JsonNode promptOverrideConfiguration) {
        this.promptOverrideConfiguration = promptOverrideConfiguration;
    }

    public JsonNode getCustomOrchestration() {
        return customOrchestration;
    }

    public void setCustomOrchestration(JsonNode customOrchestration) {
        this.customOrchestration = customOrchestration;
    }

    public String getOrchestrationType() {
        return orchestrationType;
    }

    public void setOrchestrationType(String orchestrationType) {
        this.orchestrationType = orchestrationType;
    }

    public String getAgentCollaboration() {
        return agentCollaboration;
    }

    public void setAgentCollaboration(String agentCollaboration) {
        this.agentCollaboration = agentCollaboration;
    }

    public int getNextVersion() {
        return nextVersion;
    }

    public void setNextVersion(int nextVersion) {
        this.nextVersion = nextVersion;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }
}
