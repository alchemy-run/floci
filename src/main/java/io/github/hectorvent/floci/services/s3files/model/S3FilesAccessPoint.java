package io.github.hectorvent.floci.services.s3files.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An Amazon S3 File System access point. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3FilesAccessPoint {

    private String accessPointId;
    private String accessPointArn;
    private String fileSystemId;
    private String clientToken;
    private String ownerId;
    private String status;
    private String region;
    private String name;
    private Map<String, Object> posixUser;
    private Map<String, Object> rootDirectory;
    private Map<String, String> tags = new LinkedHashMap<>();

    public S3FilesAccessPoint() {
    }

    public String getAccessPointId() {
        return accessPointId;
    }

    public void setAccessPointId(String accessPointId) {
        this.accessPointId = accessPointId;
    }

    public String getAccessPointArn() {
        return accessPointArn;
    }

    public void setAccessPointArn(String accessPointArn) {
        this.accessPointArn = accessPointArn;
    }

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getPosixUser() {
        return posixUser;
    }

    public void setPosixUser(Map<String, Object> posixUser) {
        this.posixUser = posixUser;
    }

    public Map<String, Object> getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(Map<String, Object> rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
