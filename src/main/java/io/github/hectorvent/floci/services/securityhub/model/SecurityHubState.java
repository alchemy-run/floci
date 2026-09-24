package io.github.hectorvent.floci.services.securityhub.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityHubState {
    private String adminAccountId;
    private String adminFeature = "SecurityHub";
    private boolean enabled;
    private boolean autoEnable;
    private String autoEnableStandards = "DEFAULT";
    private boolean autoEnableControls = true;
    private String controlFindingGenerator = "SECURITY_CONTROL";
    private Map<String, String> hubTags = new LinkedHashMap<>();
    private String aggregatorArn;
    private String regionLinkingMode;
    private JsonNode regions;
    private String organizationConfigurationType = "LOCAL";
    private String organizationConfigurationStatus = "ENABLED";
    private int organizationConfigurationPendingPollsRemaining;
    private Map<String, JsonNode> policies = new LinkedHashMap<>();
    private Map<String, SecurityHubAssociation> associations = new LinkedHashMap<>();
    private Map<String, JsonNode> actionTargets = new LinkedHashMap<>();
    private Map<String, JsonNode> insights = new LinkedHashMap<>();
    private Map<String, JsonNode> automationRules = new LinkedHashMap<>();
    private Map<String, JsonNode> findings = new LinkedHashMap<>();
    private Map<String, JsonNode> findingHistory = new LinkedHashMap<>();
    private Map<String, JsonNode> standardsSubscriptions = new LinkedHashMap<>();
    private Map<String, JsonNode> productSubscriptions = new LinkedHashMap<>();
    private Map<String, JsonNode> members = new LinkedHashMap<>();
    private Map<String, JsonNode> invitations = new LinkedHashMap<>();
    private JsonNode administrator;

    public Map<String, JsonNode> getActionTargets() { return actionTargets; }
    public void setActionTargets(Map<String, JsonNode> value) { actionTargets = value; }
    public Map<String, JsonNode> getInsights() { return insights; }
    public void setInsights(Map<String, JsonNode> value) { insights = value; }
    public Map<String, JsonNode> getAutomationRules() { return automationRules; }
    public void setAutomationRules(Map<String, JsonNode> value) { automationRules = value; }
    public Map<String, JsonNode> getFindings() { return findings; }
    public void setFindings(Map<String, JsonNode> value) { findings = value; }
    public Map<String, JsonNode> getFindingHistory() { return findingHistory; }
    public void setFindingHistory(Map<String, JsonNode> value) { findingHistory = value; }
    public Map<String, JsonNode> getStandardsSubscriptions() { return standardsSubscriptions; }
    public void setStandardsSubscriptions(Map<String, JsonNode> value) { standardsSubscriptions = value; }
    public Map<String, JsonNode> getProductSubscriptions() { return productSubscriptions; }
    public void setProductSubscriptions(Map<String, JsonNode> value) { productSubscriptions = value; }
    public Map<String, JsonNode> getMembers() { return members; }
    public void setMembers(Map<String, JsonNode> value) { members = value; }
    public Map<String, JsonNode> getInvitations() { return invitations; }
    public void setInvitations(Map<String, JsonNode> value) { invitations = value; }
    public JsonNode getAdministrator() { return administrator; }
    public void setAdministrator(JsonNode value) { administrator = value; }

    public String getAdminAccountId() { return adminAccountId; }
    public void setAdminAccountId(String adminAccountId) { this.adminAccountId = adminAccountId; }
    public String getAdminFeature() { return adminFeature; }
    public void setAdminFeature(String adminFeature) { this.adminFeature = adminFeature; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAutoEnable() { return autoEnable; }
    public void setAutoEnable(boolean autoEnable) { this.autoEnable = autoEnable; }
    public String getAutoEnableStandards() { return autoEnableStandards; }
    public void setAutoEnableStandards(String autoEnableStandards) { this.autoEnableStandards = autoEnableStandards; }
    public boolean isAutoEnableControls() { return autoEnableControls; }
    public void setAutoEnableControls(boolean autoEnableControls) { this.autoEnableControls = autoEnableControls; }
    public String getControlFindingGenerator() { return controlFindingGenerator; }
    public void setControlFindingGenerator(String controlFindingGenerator) { this.controlFindingGenerator = controlFindingGenerator; }
    public Map<String, String> getHubTags() { return hubTags; }
    public void setHubTags(Map<String, String> hubTags) { this.hubTags = hubTags; }
    public String getAggregatorArn() { return aggregatorArn; }
    public void setAggregatorArn(String aggregatorArn) { this.aggregatorArn = aggregatorArn; }
    public String getRegionLinkingMode() { return regionLinkingMode; }
    public void setRegionLinkingMode(String regionLinkingMode) { this.regionLinkingMode = regionLinkingMode; }
    public JsonNode getRegions() { return regions; }
    public void setRegions(JsonNode regions) { this.regions = regions; }
    public String getOrganizationConfigurationType() { return organizationConfigurationType; }
    public void setOrganizationConfigurationType(String value) { organizationConfigurationType = value; }
    public String getOrganizationConfigurationStatus() { return organizationConfigurationStatus; }
    public void setOrganizationConfigurationStatus(String value) { organizationConfigurationStatus = value; }
    public int getOrganizationConfigurationPendingPollsRemaining() { return organizationConfigurationPendingPollsRemaining; }
    public void setOrganizationConfigurationPendingPollsRemaining(int value) { organizationConfigurationPendingPollsRemaining = value; }
    public Map<String, JsonNode> getPolicies() { return policies; }
    public void setPolicies(Map<String, JsonNode> policies) { this.policies = policies; }
    public Map<String, SecurityHubAssociation> getAssociations() { return associations; }
    public void setAssociations(Map<String, SecurityHubAssociation> associations) { this.associations = associations; }
}
