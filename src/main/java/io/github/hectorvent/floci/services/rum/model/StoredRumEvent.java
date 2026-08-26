package io.github.hectorvent.floci.services.rum.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One ingested {@code PutRumEvents} telemetry event, stored for
 * {@code GetAppMonitorData}.
 */
@RegisterForReflection
public class StoredRumEvent {
    private String id;
    private long timestampMillis;
    private String type;
    private String details;
    private String metadata;
    private String batchId;
    private String userId;
    private String sessionId;
    private String alias;

    public StoredRumEvent() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public void setTimestampMillis(long timestampMillis) {
        this.timestampMillis = timestampMillis;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }
}
