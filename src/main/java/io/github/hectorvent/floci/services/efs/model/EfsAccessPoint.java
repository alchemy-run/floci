package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class EfsAccessPoint {

    private String accessPointId;
    private String accessPointArn;
    private String fileSystemId;
    private String clientToken;
    private String ownerId;
    private String lifeCycleState;
    private String region;
    private Map<String, Object> posixUser;
    private Map<String, Object> rootDirectory;
    private Map<String, String> tags = new LinkedHashMap<>();

    public EfsAccessPoint() {}

    public String getAccessPointId() { return accessPointId; }
    public void setAccessPointId(String accessPointId) { this.accessPointId = accessPointId; }

    public String getAccessPointArn() { return accessPointArn; }
    public void setAccessPointArn(String accessPointArn) { this.accessPointArn = accessPointArn; }

    public String getFileSystemId() { return fileSystemId; }
    public void setFileSystemId(String fileSystemId) { this.fileSystemId = fileSystemId; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getLifeCycleState() { return lifeCycleState; }
    public void setLifeCycleState(String lifeCycleState) { this.lifeCycleState = lifeCycleState; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, Object> getPosixUser() { return posixUser; }
    public void setPosixUser(Map<String, Object> posixUser) { this.posixUser = posixUser; }

    public Map<String, Object> getRootDirectory() { return rootDirectory; }
    public void setRootDirectory(Map<String, Object> rootDirectory) { this.rootDirectory = rootDirectory; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
