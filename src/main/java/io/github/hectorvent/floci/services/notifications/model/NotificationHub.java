package io.github.hectorvent.floci.services.notifications.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A registered AWS User Notifications hub region. */
@RegisterForReflection
public class NotificationHub {

    private String accountId;
    private String notificationHubRegion;
    private String status;
    private String statusReason;
    private String creationTime;
    private String lastActivationTime;

    public NotificationHub() {
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getNotificationHubRegion() {
        return notificationHubRegion;
    }

    public void setNotificationHubRegion(String notificationHubRegion) {
        this.notificationHubRegion = notificationHubRegion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public String getLastActivationTime() {
        return lastActivationTime;
    }

    public void setLastActivationTime(String lastActivationTime) {
        this.lastActivationTime = lastActivationTime;
    }
}
