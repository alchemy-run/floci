package io.github.hectorvent.floci.services.datasync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class DataSyncLocation {

    private String locationArn;
    private String locationUri;
    private String kind;
    private String subdirectory;
    private String s3BucketArn;
    private String s3StorageClass;
    private String bucketAccessRoleArn;
    private List<String> agentArns = new ArrayList<>();
    private String efsFilesystemArn;
    private String subnetArn;
    private List<String> securityGroupArns = new ArrayList<>();
    private String accessPointArn;
    private String fileSystemAccessRoleArn;
    private String inTransitEncryption;
    private long creationTime;
    private Map<String, String> tags = new LinkedHashMap<>();

    public DataSyncLocation() {}

    public String getLocationArn() { return locationArn; }
    public void setLocationArn(String locationArn) { this.locationArn = locationArn; }

    public String getLocationUri() { return locationUri; }
    public void setLocationUri(String locationUri) { this.locationUri = locationUri; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getSubdirectory() { return subdirectory; }
    public void setSubdirectory(String subdirectory) { this.subdirectory = subdirectory; }

    public String getS3BucketArn() { return s3BucketArn; }
    public void setS3BucketArn(String s3BucketArn) { this.s3BucketArn = s3BucketArn; }

    public String getS3StorageClass() { return s3StorageClass; }
    public void setS3StorageClass(String s3StorageClass) { this.s3StorageClass = s3StorageClass; }

    public String getBucketAccessRoleArn() { return bucketAccessRoleArn; }
    public void setBucketAccessRoleArn(String bucketAccessRoleArn) { this.bucketAccessRoleArn = bucketAccessRoleArn; }

    public List<String> getAgentArns() { return agentArns; }
    public void setAgentArns(List<String> agentArns) {
        this.agentArns = agentArns != null ? agentArns : new ArrayList<>();
    }

    public String getEfsFilesystemArn() { return efsFilesystemArn; }
    public void setEfsFilesystemArn(String efsFilesystemArn) { this.efsFilesystemArn = efsFilesystemArn; }

    public String getSubnetArn() { return subnetArn; }
    public void setSubnetArn(String subnetArn) { this.subnetArn = subnetArn; }

    public List<String> getSecurityGroupArns() { return securityGroupArns; }
    public void setSecurityGroupArns(List<String> securityGroupArns) {
        this.securityGroupArns = securityGroupArns != null ? securityGroupArns : new ArrayList<>();
    }

    public String getAccessPointArn() { return accessPointArn; }
    public void setAccessPointArn(String accessPointArn) { this.accessPointArn = accessPointArn; }

    public String getFileSystemAccessRoleArn() { return fileSystemAccessRoleArn; }
    public void setFileSystemAccessRoleArn(String fileSystemAccessRoleArn) {
        this.fileSystemAccessRoleArn = fileSystemAccessRoleArn;
    }

    public String getInTransitEncryption() { return inTransitEncryption; }
    public void setInTransitEncryption(String inTransitEncryption) {
        this.inTransitEncryption = inTransitEncryption;
    }

    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long creationTime) { this.creationTime = creationTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
