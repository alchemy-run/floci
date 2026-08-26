package io.github.hectorvent.floci.services.devopsguru.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Per-account, per-region DevOps Guru event-sources singleton. */
@RegisterForReflection
public class EventSourcesState {

    private String amazonCodeGuruProfilerStatus = "DISABLED";

    public EventSourcesState() {
    }

    public static EventSourcesState disabled() {
        EventSourcesState state = new EventSourcesState();
        state.setAmazonCodeGuruProfilerStatus("DISABLED");
        return state;
    }

    public String getAmazonCodeGuruProfilerStatus() {
        return amazonCodeGuruProfilerStatus == null ? "DISABLED" : amazonCodeGuruProfilerStatus;
    }

    public void setAmazonCodeGuruProfilerStatus(String amazonCodeGuruProfilerStatus) {
        this.amazonCodeGuruProfilerStatus = amazonCodeGuruProfilerStatus;
    }
}
