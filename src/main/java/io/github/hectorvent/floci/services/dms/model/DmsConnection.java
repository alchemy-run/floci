package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The outcome of the latest TestConnection between one replication instance and one endpoint.
 * {@code attemptId} identifies the probe that owns the record, so a probe that finishes after the
 * connection was re-tested, deleted, or reset cannot overwrite a newer record.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmsConnection {

    private String replicationInstanceArn;
    private String replicationInstanceIdentifier;
    private String endpointArn;
    private String endpointIdentifier;
    private String status;
    private String lastFailureMessage;
    private String attemptId;

    public DmsConnection() {
    }

    public String getReplicationInstanceArn() {
        return replicationInstanceArn;
    }

    public void setReplicationInstanceArn(String replicationInstanceArn) {
        this.replicationInstanceArn = replicationInstanceArn;
    }

    public String getReplicationInstanceIdentifier() {
        return replicationInstanceIdentifier;
    }

    public void setReplicationInstanceIdentifier(String replicationInstanceIdentifier) {
        this.replicationInstanceIdentifier = replicationInstanceIdentifier;
    }

    public String getEndpointArn() {
        return endpointArn;
    }

    public void setEndpointArn(String endpointArn) {
        this.endpointArn = endpointArn;
    }

    public String getEndpointIdentifier() {
        return endpointIdentifier;
    }

    public void setEndpointIdentifier(String endpointIdentifier) {
        this.endpointIdentifier = endpointIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    public void setLastFailureMessage(String lastFailureMessage) {
        this.lastFailureMessage = lastFailureMessage;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }
}
