package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Bedrock AgentCore MCP gateway. Wire names are camelCase. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Gateway {

    private String gatewayId;
    private String gatewayArn;
    private String gatewayUrl;
    private String name;
    private String description;
    private String roleArn;
    private String protocolType;
    private JsonNode protocolConfiguration;
    private String authorizerType;
    private JsonNode authorizerConfiguration;
    private String kmsKeyArn;
    private String exceptionLevel;
    private JsonNode interceptorConfigurations;
    private JsonNode policyEngineConfiguration;
    private JsonNode customTransformConfiguration;
    private JsonNode workloadIdentityDetails;
    private String status;
    private List<String> statusReasons;
    private String createdAt;
    private String updatedAt;
    private Map<String, String> tags;

    public Gateway() {
    }

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public String getGatewayArn() {
        return gatewayArn;
    }

    public void setGatewayArn(String gatewayArn) {
        this.gatewayArn = gatewayArn;
    }

    public String getGatewayUrl() {
        return gatewayUrl;
    }

    public void setGatewayUrl(String gatewayUrl) {
        this.gatewayUrl = gatewayUrl;
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

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getProtocolType() {
        return protocolType;
    }

    public void setProtocolType(String protocolType) {
        this.protocolType = protocolType;
    }

    public JsonNode getProtocolConfiguration() {
        return copy(protocolConfiguration);
    }

    public void setProtocolConfiguration(JsonNode protocolConfiguration) {
        this.protocolConfiguration = copy(protocolConfiguration);
    }

    public String getAuthorizerType() {
        return authorizerType;
    }

    public void setAuthorizerType(String authorizerType) {
        this.authorizerType = authorizerType;
    }

    public JsonNode getAuthorizerConfiguration() {
        return copy(authorizerConfiguration);
    }

    public void setAuthorizerConfiguration(JsonNode authorizerConfiguration) {
        this.authorizerConfiguration = copy(authorizerConfiguration);
    }

    public String getKmsKeyArn() {
        return kmsKeyArn;
    }

    public void setKmsKeyArn(String kmsKeyArn) {
        this.kmsKeyArn = kmsKeyArn;
    }

    public String getExceptionLevel() {
        return exceptionLevel;
    }

    public void setExceptionLevel(String exceptionLevel) {
        this.exceptionLevel = exceptionLevel;
    }

    public JsonNode getInterceptorConfigurations() {
        return copy(interceptorConfigurations);
    }

    public void setInterceptorConfigurations(JsonNode interceptorConfigurations) {
        this.interceptorConfigurations = copy(interceptorConfigurations);
    }

    public JsonNode getPolicyEngineConfiguration() {
        return copy(policyEngineConfiguration);
    }

    public void setPolicyEngineConfiguration(JsonNode policyEngineConfiguration) {
        this.policyEngineConfiguration = copy(policyEngineConfiguration);
    }

    public JsonNode getCustomTransformConfiguration() {
        return copy(customTransformConfiguration);
    }

    public void setCustomTransformConfiguration(JsonNode customTransformConfiguration) {
        this.customTransformConfiguration = copy(customTransformConfiguration);
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

    public List<String> getStatusReasons() {
        return statusReasons == null ? null : List.copyOf(statusReasons);
    }

    public void setStatusReasons(List<String> statusReasons) {
        this.statusReasons = statusReasons == null ? null : new ArrayList<>(statusReasons);
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
