package io.github.hectorvent.floci.services.cloudhsmv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudHsmCluster {

    private String clusterId;
    private String region;
    private String hsmType;
    private String state;
    private String stateMessage;
    private String vpcId;
    private String securityGroup;
    private String sourceBackupId;
    private String networkType;
    private String mode;
    private String backupPolicy;
    private String backupRetentionType;
    private String backupRetentionValue;
    private String clusterCsr;
    private long createTimestamp;
    private Map<String, String> subnetMapping = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<CloudHsm> hsms = new ArrayList<>();

    public CloudHsmCluster() {
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getHsmType() {
        return hsmType;
    }

    public void setHsmType(String hsmType) {
        this.hsmType = hsmType;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateMessage() {
        return stateMessage;
    }

    public void setStateMessage(String stateMessage) {
        this.stateMessage = stateMessage;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public String getSecurityGroup() {
        return securityGroup;
    }

    public void setSecurityGroup(String securityGroup) {
        this.securityGroup = securityGroup;
    }

    public String getSourceBackupId() {
        return sourceBackupId;
    }

    public void setSourceBackupId(String sourceBackupId) {
        this.sourceBackupId = sourceBackupId;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getBackupPolicy() {
        return backupPolicy;
    }

    public void setBackupPolicy(String backupPolicy) {
        this.backupPolicy = backupPolicy;
    }

    public String getBackupRetentionType() {
        return backupRetentionType;
    }

    public void setBackupRetentionType(String backupRetentionType) {
        this.backupRetentionType = backupRetentionType;
    }

    public String getBackupRetentionValue() {
        return backupRetentionValue;
    }

    public void setBackupRetentionValue(String backupRetentionValue) {
        this.backupRetentionValue = backupRetentionValue;
    }

    public String getClusterCsr() {
        return clusterCsr;
    }

    public void setClusterCsr(String clusterCsr) {
        this.clusterCsr = clusterCsr;
    }

    public long getCreateTimestamp() {
        return createTimestamp;
    }

    public void setCreateTimestamp(long createTimestamp) {
        this.createTimestamp = createTimestamp;
    }

    public Map<String, String> getSubnetMapping() {
        return subnetMapping;
    }

    public void setSubnetMapping(Map<String, String> subnetMapping) {
        this.subnetMapping = subnetMapping != null ? subnetMapping : new LinkedHashMap<>();
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public List<CloudHsm> getHsms() {
        return hsms;
    }

    public void setHsms(List<CloudHsm> hsms) {
        this.hsms = hsms != null ? hsms : new ArrayList<>();
    }
}
