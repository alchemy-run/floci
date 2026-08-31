package io.github.hectorvent.floci.services.entityresolution.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Entity Resolution ID namespace. Wire names are camelCase restJson1. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IdNamespace {

    private String idNamespaceName;
    private String idNamespaceArn;
    private String description;
    private String type;
    private JsonNode inputSourceConfig;
    private JsonNode idMappingWorkflowProperties;
    private String roleArn;
    private Map<String, String> tags;
    private long createdAt;
    private long updatedAt;

    public IdNamespace() {
    }

    public String getIdNamespaceName() {
        return idNamespaceName;
    }

    public void setIdNamespaceName(String idNamespaceName) {
        this.idNamespaceName = idNamespaceName;
    }

    public String getIdNamespaceArn() {
        return idNamespaceArn;
    }

    public void setIdNamespaceArn(String idNamespaceArn) {
        this.idNamespaceArn = idNamespaceArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JsonNode getInputSourceConfig() {
        return inputSourceConfig;
    }

    public void setInputSourceConfig(JsonNode inputSourceConfig) {
        this.inputSourceConfig = inputSourceConfig;
    }

    public JsonNode getIdMappingWorkflowProperties() {
        return idMappingWorkflowProperties;
    }

    public void setIdMappingWorkflowProperties(JsonNode idMappingWorkflowProperties) {
        this.idMappingWorkflowProperties = idMappingWorkflowProperties;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : new LinkedHashMap<>(tags);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
