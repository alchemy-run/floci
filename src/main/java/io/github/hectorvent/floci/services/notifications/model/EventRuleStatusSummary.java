package io.github.hectorvent.floci.services.notifications.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Per-region status of a User Notifications event rule. */
@RegisterForReflection
public class EventRuleStatusSummary {

    private String status;
    private String reason;

    public EventRuleStatusSummary() {
    }

    public EventRuleStatusSummary(String status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
