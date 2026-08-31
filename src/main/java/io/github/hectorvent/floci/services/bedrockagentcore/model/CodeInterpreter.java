package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A Bedrock AgentCore custom code interpreter. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeInterpreter {

    private String codeInterpreterId;
    private String codeInterpreterArn;
    private String name;
    private String description;
    private String executionRoleArn;
    private JsonNode networkConfiguration;
    private JsonNode certificates;
    private String status;
    private String failureReason;
    private String createdAt;
    private String lastUpdatedAt;
    private Map<String, String> tags;

    public CodeInterpreter() {
    }

    public String getCodeInterpreterId() {
        return codeInterpreterId;
    }

    public void setCodeInterpreterId(String codeInterpreterId) {
        this.codeInterpreterId = codeInterpreterId;
    }

    public String getCodeInterpreterArn() {
        return codeInterpreterArn;
    }

    public void setCodeInterpreterArn(String codeInterpreterArn) {
        this.codeInterpreterArn = codeInterpreterArn;
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
        return networkConfiguration == null ? null : networkConfiguration.deepCopy();
    }

    public void setNetworkConfiguration(JsonNode networkConfiguration) {
        this.networkConfiguration = networkConfiguration == null ? null : networkConfiguration.deepCopy();
    }

    public JsonNode getCertificates() {
        return certificates == null ? null : certificates.deepCopy();
    }

    public void setCertificates(JsonNode certificates) {
        this.certificates = certificates == null ? null : certificates.deepCopy();
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
}
