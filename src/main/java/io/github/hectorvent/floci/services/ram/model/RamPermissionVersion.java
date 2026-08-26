package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** One version of a RAM customer managed permission. */
@RegisterForReflection
public class RamPermissionVersion {
    private int version;
    private String policyTemplate;
    private String status;
    private long creationTime;
    private long lastUpdatedTime;

    public RamPermissionVersion() {
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getPolicyTemplate() {
        return policyTemplate;
    }

    public void setPolicyTemplate(String policyTemplate) {
        this.policyTemplate = policyTemplate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
}
