package io.github.hectorvent.floci.services.identitycenter.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SsoPermissionSet {

    private String instanceArn;
    private String permissionSetArn;
    private String name;
    private String description;
    private String sessionDuration;
    private String relayState;
    private long createdDate;

    public SsoPermissionSet() {
    }

    public String getInstanceArn() {
        return instanceArn;
    }

    public void setInstanceArn(String instanceArn) {
        this.instanceArn = instanceArn;
    }

    public String getPermissionSetArn() {
        return permissionSetArn;
    }

    public void setPermissionSetArn(String permissionSetArn) {
        this.permissionSetArn = permissionSetArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSessionDuration() {
        return sessionDuration;
    }

    public void setSessionDuration(String sessionDuration) {
        this.sessionDuration = sessionDuration;
    }

    public String getRelayState() {
        return relayState;
    }

    public void setRelayState(String relayState) {
        this.relayState = relayState;
    }

    public long getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(long createdDate) {
        this.createdDate = createdDate;
    }
}
