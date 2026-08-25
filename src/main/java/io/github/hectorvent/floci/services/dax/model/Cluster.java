package io.github.hectorvent.floci.services.dax.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Cluster {

    private String clusterName;
    private String description;
    private String clusterArn;
    private String nodeType;
    private String status;
    private String discoveryAddress;
    private int discoveryPort;
    private String discoveryUrl;
    private String preferredMaintenanceWindow;
    private String notificationTopicArn;
    private String subnetGroupName;
    private String iamRoleArn;
    private String parameterGroupName;
    private boolean sseEnabled;
    private String clusterEndpointEncryptionType;
    private String networkType;
    private String region;
    private List<String> availabilityZones = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private List<Node> nodes = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public Cluster() {}

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDiscoveryAddress() { return discoveryAddress; }
    public void setDiscoveryAddress(String discoveryAddress) { this.discoveryAddress = discoveryAddress; }

    public int getDiscoveryPort() { return discoveryPort; }
    public void setDiscoveryPort(int discoveryPort) { this.discoveryPort = discoveryPort; }

    public String getDiscoveryUrl() { return discoveryUrl; }
    public void setDiscoveryUrl(String discoveryUrl) { this.discoveryUrl = discoveryUrl; }

    public String getPreferredMaintenanceWindow() { return preferredMaintenanceWindow; }
    public void setPreferredMaintenanceWindow(String preferredMaintenanceWindow) {
        this.preferredMaintenanceWindow = preferredMaintenanceWindow;
    }

    public String getNotificationTopicArn() { return notificationTopicArn; }
    public void setNotificationTopicArn(String notificationTopicArn) {
        this.notificationTopicArn = notificationTopicArn;
    }

    public String getSubnetGroupName() { return subnetGroupName; }
    public void setSubnetGroupName(String subnetGroupName) { this.subnetGroupName = subnetGroupName; }

    public String getIamRoleArn() { return iamRoleArn; }
    public void setIamRoleArn(String iamRoleArn) { this.iamRoleArn = iamRoleArn; }

    public String getParameterGroupName() { return parameterGroupName; }
    public void setParameterGroupName(String parameterGroupName) { this.parameterGroupName = parameterGroupName; }

    public boolean isSseEnabled() { return sseEnabled; }
    public void setSseEnabled(boolean sseEnabled) { this.sseEnabled = sseEnabled; }

    public String getClusterEndpointEncryptionType() { return clusterEndpointEncryptionType; }
    public void setClusterEndpointEncryptionType(String clusterEndpointEncryptionType) {
        this.clusterEndpointEncryptionType = clusterEndpointEncryptionType;
    }

    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<String> getAvailabilityZones() { return availabilityZones; }
    public void setAvailabilityZones(List<String> availabilityZones) {
        this.availabilityZones = availabilityZones != null ? availabilityZones : new ArrayList<>();
    }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? securityGroupIds : new ArrayList<>();
    }

    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
