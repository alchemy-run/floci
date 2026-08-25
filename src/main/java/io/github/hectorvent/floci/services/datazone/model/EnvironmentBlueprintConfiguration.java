package io.github.hectorvent.floci.services.datazone.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-domain configuration of a managed DataZone environment blueprint. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnvironmentBlueprintConfiguration {

    private String domainId;
    private String environmentBlueprintId;
    private String provisioningRoleArn;
    private String manageAccessRoleArn;
    private String environmentRolePermissionBoundary;
    private List<String> enabledRegions = new ArrayList<>();
    private Map<String, Map<String, String>> regionalParameters = new LinkedHashMap<>();
    private Map<String, String> globalParameters = new LinkedHashMap<>();
    private JsonNode provisioningConfigurations;
    private JsonNode resourceConfigurations;
    private Boolean allowUserProvidedConfigurations;
    private String createdAt;
    private String updatedAt;
    private String region;

    public EnvironmentBlueprintConfiguration() {
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getEnvironmentBlueprintId() {
        return environmentBlueprintId;
    }

    public void setEnvironmentBlueprintId(String environmentBlueprintId) {
        this.environmentBlueprintId = environmentBlueprintId;
    }

    public String getProvisioningRoleArn() {
        return provisioningRoleArn;
    }

    public void setProvisioningRoleArn(String provisioningRoleArn) {
        this.provisioningRoleArn = provisioningRoleArn;
    }

    public String getManageAccessRoleArn() {
        return manageAccessRoleArn;
    }

    public void setManageAccessRoleArn(String manageAccessRoleArn) {
        this.manageAccessRoleArn = manageAccessRoleArn;
    }

    public String getEnvironmentRolePermissionBoundary() {
        return environmentRolePermissionBoundary;
    }

    public void setEnvironmentRolePermissionBoundary(String environmentRolePermissionBoundary) {
        this.environmentRolePermissionBoundary = environmentRolePermissionBoundary;
    }

    public List<String> getEnabledRegions() {
        return enabledRegions;
    }

    public void setEnabledRegions(List<String> enabledRegions) {
        this.enabledRegions = enabledRegions == null ? new ArrayList<>() : new ArrayList<>(enabledRegions);
    }

    public Map<String, Map<String, String>> getRegionalParameters() {
        return regionalParameters;
    }

    public void setRegionalParameters(Map<String, Map<String, String>> regionalParameters) {
        this.regionalParameters = regionalParameters == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(regionalParameters);
    }

    public Map<String, String> getGlobalParameters() {
        return globalParameters;
    }

    public void setGlobalParameters(Map<String, String> globalParameters) {
        this.globalParameters = globalParameters == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(globalParameters);
    }

    public JsonNode getProvisioningConfigurations() {
        return provisioningConfigurations;
    }

    public void setProvisioningConfigurations(JsonNode provisioningConfigurations) {
        this.provisioningConfigurations = provisioningConfigurations;
    }

    public JsonNode getResourceConfigurations() {
        return resourceConfigurations;
    }

    public void setResourceConfigurations(JsonNode resourceConfigurations) {
        this.resourceConfigurations = resourceConfigurations;
    }

    public Boolean getAllowUserProvidedConfigurations() {
        return allowUserProvidedConfigurations;
    }

    public void setAllowUserProvidedConfigurations(Boolean allowUserProvidedConfigurations) {
        this.allowUserProvidedConfigurations = allowUserProvidedConfigurations;
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

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
