package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class ClusterEvent {

    private String sourceIdentifier;
    private String sourceType;
    private String message;
    private String severity;
    private String eventId;
    private Instant date;

    public ClusterEvent() {}

    public ClusterEvent(String sourceIdentifier, String sourceType, String message,
                        String severity, String eventId, Instant date) {
        this.sourceIdentifier = sourceIdentifier;
        this.sourceType = sourceType;
        this.message = message;
        this.severity = severity;
        this.eventId = eventId;
        this.date = date;
    }

    public String getSourceIdentifier() { return sourceIdentifier; }
    public void setSourceIdentifier(String sourceIdentifier) { this.sourceIdentifier = sourceIdentifier; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Instant getDate() { return date; }
    public void setDate(Instant date) { this.date = date; }
}
