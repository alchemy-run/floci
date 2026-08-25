package io.github.hectorvent.floci.services.fsx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class FsxFileSystem {

    private String fileSystemId;
    private String region;
    private String ownerId;
    private String clientRequestToken;
    private long creationTime;
    private String fileSystemType;
    private String lifecycle;
    private Integer storageCapacity;
    private String storageType;
    private String vpcId;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private List<String> networkInterfaceIds = new ArrayList<>();
    private String dnsName;
    private String kmsKeyId;
    private String resourceArn;
    private String fileSystemTypeVersion;
    private String networkType;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, Object> lustreConfiguration;
    private Map<String, Object> windowsConfiguration;
    private Map<String, Object> ontapConfiguration;
    private Map<String, Object> openZFSConfiguration;

    public FsxFileSystem() {
    }

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getClientRequestToken() {
        return clientRequestToken;
    }

    public void setClientRequestToken(String clientRequestToken) {
        this.clientRequestToken = clientRequestToken;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public String getFileSystemType() {
        return fileSystemType;
    }

    public void setFileSystemType(String fileSystemType) {
        this.fileSystemType = fileSystemType;
    }

    public String getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(String lifecycle) {
        this.lifecycle = lifecycle;
    }

    public Integer getStorageCapacity() {
        return storageCapacity;
    }

    public void setStorageCapacity(Integer storageCapacity) {
        this.storageCapacity = storageCapacity;
    }

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public String getVpcId() {
        return vpcId;
    }

    public void setVpcId(String vpcId) {
        this.vpcId = vpcId;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds != null ? subnetIds : new ArrayList<>();
    }

    public List<String> getSecurityGroupIds() {
        return securityGroupIds;
    }

    public void setSecurityGroupIds(List<String> securityGroupIds) {
        this.securityGroupIds = securityGroupIds != null ? securityGroupIds : new ArrayList<>();
    }

    public List<String> getNetworkInterfaceIds() {
        return networkInterfaceIds;
    }

    public void setNetworkInterfaceIds(List<String> networkInterfaceIds) {
        this.networkInterfaceIds = networkInterfaceIds != null ? networkInterfaceIds : new ArrayList<>();
    }

    public String getDnsName() {
        return dnsName;
    }

    public void setDnsName(String dnsName) {
        this.dnsName = dnsName;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getFileSystemTypeVersion() {
        return fileSystemTypeVersion;
    }

    public void setFileSystemTypeVersion(String fileSystemTypeVersion) {
        this.fileSystemTypeVersion = fileSystemTypeVersion;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }

    public Map<String, Object> getLustreConfiguration() {
        return lustreConfiguration;
    }

    public void setLustreConfiguration(Map<String, Object> lustreConfiguration) {
        this.lustreConfiguration = lustreConfiguration;
    }

    public Map<String, Object> getWindowsConfiguration() {
        return windowsConfiguration;
    }

    public void setWindowsConfiguration(Map<String, Object> windowsConfiguration) {
        this.windowsConfiguration = windowsConfiguration;
    }

    public Map<String, Object> getOntapConfiguration() {
        return ontapConfiguration;
    }

    public void setOntapConfiguration(Map<String, Object> ontapConfiguration) {
        this.ontapConfiguration = ontapConfiguration;
    }

    public Map<String, Object> getOpenZFSConfiguration() {
        return openZFSConfiguration;
    }

    public void setOpenZFSConfiguration(Map<String, Object> openZFSConfiguration) {
        this.openZFSConfiguration = openZFSConfiguration;
    }
}
