package io.github.hectorvent.floci.services.rum.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Per-app-monitor log of ingested RUM events. */
@RegisterForReflection
public class RumEventLog {
    private List<StoredRumEvent> events = new ArrayList<>();

    public RumEventLog() {
    }

    public List<StoredRumEvent> getEvents() {
        return events == null ? List.of() : List.copyOf(events);
    }

    public void setEvents(List<StoredRumEvent> events) {
        this.events = events == null ? new ArrayList<>() : new ArrayList<>(events);
    }
}
