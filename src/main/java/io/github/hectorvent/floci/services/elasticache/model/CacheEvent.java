package io.github.hectorvent.floci.services.elasticache.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class CacheEvent {

    private String sourceIdentifier;
    private String sourceType;
    private String message;
    private Instant date;

    public CacheEvent() {}

    public CacheEvent(String sourceIdentifier, String sourceType, String message, Instant date) {
        this.sourceIdentifier = sourceIdentifier;
        this.sourceType = sourceType;
        this.message = message;
        this.date = date;
    }

    public String getSourceIdentifier() { return sourceIdentifier; }
    public void setSourceIdentifier(String sourceIdentifier) { this.sourceIdentifier = sourceIdentifier; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Instant getDate() { return date; }
    public void setDate(Instant date) { this.date = date; }
}
