package io.github.hectorvent.floci.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Expiry notification on a trust anchor. */
@RegisterForReflection
public class NotificationSetting {
    private boolean enabled = true;
    private String event;
    private Integer threshold;
    private String channel;
    private String configuredBy;

    public NotificationSetting() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getConfiguredBy() {
        return configuredBy;
    }

    public void setConfiguredBy(String configuredBy) {
        this.configuredBy = configuredBy;
    }
}
