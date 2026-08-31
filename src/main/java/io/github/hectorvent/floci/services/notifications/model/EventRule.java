package io.github.hectorvent.floci.services.notifications.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS User Notifications event rule. */
@RegisterForReflection
public class EventRule {

    private String id;
    private String arn;
    private String accountId;
    private String notificationConfigurationArn;
    private String source;
    private String eventType;
    private String eventPattern;
    private List<String> regions = new ArrayList<>();
    private List<String> managedRules = new ArrayList<>();
    private Map<String, EventRuleStatusSummary> statusSummaryByRegion = new LinkedHashMap<>();
    private String creationTime;

    public EventRule() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getNotificationConfigurationArn() {
        return notificationConfigurationArn;
    }

    public void setNotificationConfigurationArn(String notificationConfigurationArn) {
        this.notificationConfigurationArn = notificationConfigurationArn;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getEventPattern() {
        return eventPattern;
    }

    public void setEventPattern(String eventPattern) {
        this.eventPattern = eventPattern;
    }

    public List<String> getRegions() {
        return regions;
    }

    public void setRegions(List<String> regions) {
        this.regions = regions == null ? new ArrayList<>() : new ArrayList<>(regions);
    }

    public List<String> getManagedRules() {
        return managedRules;
    }

    public void setManagedRules(List<String> managedRules) {
        this.managedRules = managedRules == null ? new ArrayList<>() : new ArrayList<>(managedRules);
    }

    public Map<String, EventRuleStatusSummary> getStatusSummaryByRegion() {
        return statusSummaryByRegion;
    }

    public void setStatusSummaryByRegion(Map<String, EventRuleStatusSummary> statusSummaryByRegion) {
        this.statusSummaryByRegion = statusSummaryByRegion == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(statusSummaryByRegion);
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }
}
