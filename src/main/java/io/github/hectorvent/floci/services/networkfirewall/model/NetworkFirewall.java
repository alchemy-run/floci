package io.github.hectorvent.floci.services.networkfirewall.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkFirewall {

    private String name;
    private String arn;
    private String id;
    private String region;
    private String firewallPolicyArn;
    private String vpcId;
    private String description;
    private String updateToken;
    private boolean deleteProtection;
    private boolean subnetChangeProtection;
    private boolean firewallPolicyChangeProtection;
    private List<NetworkFirewallSubnetMapping> subnetMappings = new ArrayList<>();
    private List<String> enabledAnalysisTypes = new ArrayList<>();
    private List<Map<String, Object>> logDestinationConfigs = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public NetworkFirewall() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getFirewallPolicyArn() {
        return firewallPolicyArn;
    }

    public void setFirewallPolicyArn(String firewallPolicyArn) {
        this.firewallPolicyArn = firewallPolicyArn;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUpdateToken() {
        return updateToken;
    }

    public void setUpdateToken(String updateToken) {
        this.updateToken = updateToken;
    }

    public boolean isDeleteProtection() {
        return deleteProtection;
    }

    public void setDeleteProtection(boolean deleteProtection) {
        this.deleteProtection = deleteProtection;
    }

    public boolean isSubnetChangeProtection() {
        return subnetChangeProtection;
    }

    public void setSubnetChangeProtection(boolean subnetChangeProtection) {
        this.subnetChangeProtection = subnetChangeProtection;
    }

    public boolean isFirewallPolicyChangeProtection() {
        return firewallPolicyChangeProtection;
    }

    public void setFirewallPolicyChangeProtection(boolean firewallPolicyChangeProtection) {
        this.firewallPolicyChangeProtection = firewallPolicyChangeProtection;
    }

    public List<NetworkFirewallSubnetMapping> getSubnetMappings() {
        return subnetMappings;
    }

    public void setSubnetMappings(List<NetworkFirewallSubnetMapping> subnetMappings) {
        this.subnetMappings = subnetMappings != null ? subnetMappings : new ArrayList<>();
    }

    public List<String> getEnabledAnalysisTypes() {
        return enabledAnalysisTypes;
    }

    public void setEnabledAnalysisTypes(List<String> enabledAnalysisTypes) {
        this.enabledAnalysisTypes = enabledAnalysisTypes != null ? enabledAnalysisTypes : new ArrayList<>();
    }

    public List<Map<String, Object>> getLogDestinationConfigs() {
        return logDestinationConfigs;
    }

    public void setLogDestinationConfigs(List<Map<String, Object>> logDestinationConfigs) {
        this.logDestinationConfigs = logDestinationConfigs != null ? logDestinationConfigs : new ArrayList<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
