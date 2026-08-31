package io.github.hectorvent.floci.services.redshift.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RedshiftCluster {

    private String clusterIdentifier;
    private String nodeType;
    private String clusterStatus = "available";
    private String clusterAvailabilityStatus = "Available";
    private String masterUsername;
    private String dbName = "dev";
    private String endpointAddress;
    private int endpointPort = 5439;
    private Instant clusterCreateTime;
    private int automatedSnapshotRetentionPeriod = 1;
    private String clusterSubnetGroupName;
    private String vpcId;
    private String availabilityZone;
    private String preferredMaintenanceWindow;
    private String clusterVersion = "1.0";
    private boolean allowVersionUpgrade = true;
    private int numberOfNodes = 1;
    private boolean publiclyAccessible;
    private boolean encrypted = true;
    private String kmsKeyId;
    private boolean enhancedVpcRouting;
    private String clusterNamespaceArn;
    private String masterPasswordSecretArn;
    private String clusterParameterGroupName;
    private List<String> vpcSecurityGroupIds = new ArrayList<>();
    private List<String> iamRoles = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public RedshiftCluster() {}

    public String getClusterIdentifier() { return clusterIdentifier; }
    public void setClusterIdentifier(String clusterIdentifier) { this.clusterIdentifier = clusterIdentifier; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getClusterStatus() { return clusterStatus; }
    public void setClusterStatus(String clusterStatus) { this.clusterStatus = clusterStatus; }

    public String getClusterAvailabilityStatus() { return clusterAvailabilityStatus; }
    public void setClusterAvailabilityStatus(String clusterAvailabilityStatus) {
        this.clusterAvailabilityStatus = clusterAvailabilityStatus;
    }

    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }

    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }

    public String getEndpointAddress() { return endpointAddress; }
    public void setEndpointAddress(String endpointAddress) { this.endpointAddress = endpointAddress; }

    public int getEndpointPort() { return endpointPort; }
    public void setEndpointPort(int endpointPort) { this.endpointPort = endpointPort; }

    public Instant getClusterCreateTime() { return clusterCreateTime; }
    public void setClusterCreateTime(Instant clusterCreateTime) { this.clusterCreateTime = clusterCreateTime; }

    public int getAutomatedSnapshotRetentionPeriod() { return automatedSnapshotRetentionPeriod; }
    public void setAutomatedSnapshotRetentionPeriod(int automatedSnapshotRetentionPeriod) {
        this.automatedSnapshotRetentionPeriod = automatedSnapshotRetentionPeriod;
    }

    public String getClusterSubnetGroupName() { return clusterSubnetGroupName; }
    public void setClusterSubnetGroupName(String clusterSubnetGroupName) {
        this.clusterSubnetGroupName = clusterSubnetGroupName;
    }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getPreferredMaintenanceWindow() { return preferredMaintenanceWindow; }
    public void setPreferredMaintenanceWindow(String preferredMaintenanceWindow) {
        this.preferredMaintenanceWindow = preferredMaintenanceWindow;
    }

    public String getClusterVersion() { return clusterVersion; }
    public void setClusterVersion(String clusterVersion) { this.clusterVersion = clusterVersion; }

    public boolean isAllowVersionUpgrade() { return allowVersionUpgrade; }
    public void setAllowVersionUpgrade(boolean allowVersionUpgrade) {
        this.allowVersionUpgrade = allowVersionUpgrade;
    }

    public int getNumberOfNodes() { return numberOfNodes; }
    public void setNumberOfNodes(int numberOfNodes) { this.numberOfNodes = numberOfNodes; }

    public boolean isPubliclyAccessible() { return publiclyAccessible; }
    public void setPubliclyAccessible(boolean publiclyAccessible) {
        this.publiclyAccessible = publiclyAccessible;
    }

    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public boolean isEnhancedVpcRouting() { return enhancedVpcRouting; }
    public void setEnhancedVpcRouting(boolean enhancedVpcRouting) {
        this.enhancedVpcRouting = enhancedVpcRouting;
    }

    public String getClusterNamespaceArn() { return clusterNamespaceArn; }
    public void setClusterNamespaceArn(String clusterNamespaceArn) {
        this.clusterNamespaceArn = clusterNamespaceArn;
    }

    public String getMasterPasswordSecretArn() { return masterPasswordSecretArn; }
    public void setMasterPasswordSecretArn(String masterPasswordSecretArn) {
        this.masterPasswordSecretArn = masterPasswordSecretArn;
    }

    public String getClusterParameterGroupName() { return clusterParameterGroupName; }
    public void setClusterParameterGroupName(String clusterParameterGroupName) {
        this.clusterParameterGroupName = clusterParameterGroupName;
    }

    public List<String> getVpcSecurityGroupIds() { return vpcSecurityGroupIds; }
    public void setVpcSecurityGroupIds(List<String> vpcSecurityGroupIds) {
        this.vpcSecurityGroupIds = vpcSecurityGroupIds != null
                ? new ArrayList<>(vpcSecurityGroupIds) : new ArrayList<>();
    }

    public List<String> getIamRoles() { return iamRoles; }
    public void setIamRoles(List<String> iamRoles) {
        this.iamRoles = iamRoles != null ? new ArrayList<>(iamRoles) : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
