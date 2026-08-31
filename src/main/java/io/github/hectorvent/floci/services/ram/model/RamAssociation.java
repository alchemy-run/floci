package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Principal, resource, or source association on a RAM resource share. */
@RegisterForReflection
public class RamAssociation {
    private String resourceShareArn;
    private String resourceShareName;
    private String associatedEntity;
    private String associationType;
    private String status;
    private String statusMessage;
    private long creationTime;
    private long lastUpdatedTime;
    private boolean external;
    private String resourceType;

    public RamAssociation() {
    }

    public String getResourceShareArn() {
        return resourceShareArn;
    }

    public void setResourceShareArn(String resourceShareArn) {
        this.resourceShareArn = resourceShareArn;
    }

    public String getResourceShareName() {
        return resourceShareName;
    }

    public void setResourceShareName(String resourceShareName) {
        this.resourceShareName = resourceShareName;
    }

    public String getAssociatedEntity() {
        return associatedEntity;
    }

    public void setAssociatedEntity(String associatedEntity) {
        this.associatedEntity = associatedEntity;
    }

    public String getAssociationType() {
        return associationType;
    }

    public void setAssociationType(String associationType) {
        this.associationType = associationType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
    }

    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }

    public boolean isExternal() {
        return external;
    }

    public void setExternal(boolean external) {
        this.external = external;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }
}
