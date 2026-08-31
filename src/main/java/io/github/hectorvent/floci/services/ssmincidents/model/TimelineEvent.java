package io.github.hectorvent.floci.services.ssmincidents.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Custom timeline event on an incident record. */
@RegisterForReflection
public class TimelineEvent {

    private String incidentRecordArn;
    private String eventId;
    private long eventTime;
    private long eventUpdatedTime;
    private String eventType;
    private String eventData;

    public TimelineEvent() {
    }

    public String getIncidentRecordArn() {
        return incidentRecordArn;
    }

    public void setIncidentRecordArn(String incidentRecordArn) {
        this.incidentRecordArn = incidentRecordArn;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public long getEventTime() {
        return eventTime;
    }

    public void setEventTime(long eventTime) {
        this.eventTime = eventTime;
    }

    public long getEventUpdatedTime() {
        return eventUpdatedTime;
    }

    public void setEventUpdatedTime(long eventUpdatedTime) {
        this.eventUpdatedTime = eventUpdatedTime;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventData() {
        return eventData;
    }

    public void setEventData(String eventData) {
        this.eventData = eventData;
    }
}
