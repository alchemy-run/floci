package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The latest RefreshSchemas run for one endpoint, together with the schema names it read.
 * {@code attemptId} guards against a stale probe overwriting a newer refresh, as in
 * {@link DmsConnection}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaRefresh {

    private String endpointArn;
    private String replicationInstanceArn;
    private String status;
    private Long lastRefreshDateMillis;
    private String lastFailureMessage;
    private List<String> schemas = new ArrayList<>();
    private String attemptId;

    public SchemaRefresh() {
    }

    public String getEndpointArn() {
        return endpointArn;
    }

    public void setEndpointArn(String endpointArn) {
        this.endpointArn = endpointArn;
    }

    public String getReplicationInstanceArn() {
        return replicationInstanceArn;
    }

    public void setReplicationInstanceArn(String replicationInstanceArn) {
        this.replicationInstanceArn = replicationInstanceArn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getLastRefreshDateMillis() {
        return lastRefreshDateMillis;
    }

    public void setLastRefreshDateMillis(Long lastRefreshDateMillis) {
        this.lastRefreshDateMillis = lastRefreshDateMillis;
    }

    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    public void setLastFailureMessage(String lastFailureMessage) {
        this.lastFailureMessage = lastFailureMessage;
    }

    public List<String> getSchemas() {
        return List.copyOf(schemas);
    }

    public void setSchemas(List<String> schemas) {
        this.schemas = schemas != null ? new ArrayList<>(schemas) : new ArrayList<>();
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }
}
