package io.github.hectorvent.floci.services.rum.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Telemetry events ingested through PutRumEvents for one app monitor, oldest first. */
@RegisterForReflection
public class RumEventLog {
    private List<Event> events = new ArrayList<>();

    public RumEventLog() {
    }

    public RumEventLog(List<Event> events) {
        setEvents(events);
    }

    public List<Event> getEvents() {
        return new ArrayList<>(events);
    }

    public void setEvents(List<Event> events) {
        this.events = events == null ? new ArrayList<>() : new ArrayList<>(events);
    }

    /** One stored event: its timestamp in epoch milliseconds and its rendered JSON document. */
    @RegisterForReflection
    public static class Event {
        private long timestampMillis;
        private String document;

        public Event() {
        }

        public Event(long timestampMillis, String document) {
            this.timestampMillis = timestampMillis;
            this.document = document;
        }

        public long getTimestampMillis() {
            return timestampMillis;
        }

        public void setTimestampMillis(long timestampMillis) {
            this.timestampMillis = timestampMillis;
        }

        public String getDocument() {
            return document;
        }

        public void setDocument(String document) {
            this.document = document;
        }
    }
}
