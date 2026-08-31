package io.github.hectorvent.floci.services.ssmincidents.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Incident Manager replication-set region entry. */
@RegisterForReflection
public class RegionInfo {

    private String sseKmsKeyId;
    private String status;
    private String statusMessage;
    private long statusUpdateDateTime;

    public RegionInfo() {
    }

    public String getSseKmsKeyId() {
        return sseKmsKeyId;
    }

    public void setSseKmsKeyId(String sseKmsKeyId) {
        this.sseKmsKeyId = sseKmsKeyId;
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

    public long getStatusUpdateDateTime() {
        return statusUpdateDateTime;
    }

    public void setStatusUpdateDateTime(long statusUpdateDateTime) {
        this.statusUpdateDateTime = statusUpdateDateTime;
    }
}
