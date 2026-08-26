package io.github.hectorvent.floci.services.xray.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** X-Ray group (filter expression + insights settings). */
@RegisterForReflection
public class XRayGroup {
    private String groupId;
    private String groupName;
    private String groupArn;
    private String filterExpression;
    private boolean insightsEnabled;
    private boolean notificationsEnabled;
    private Map<String, String> tags = new LinkedHashMap<>();

    public XRayGroup() {
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupArn() {
        return groupArn;
    }

    public void setGroupArn(String groupArn) {
        this.groupArn = groupArn;
    }

    public String getFilterExpression() {
        return filterExpression;
    }

    public void setFilterExpression(String filterExpression) {
        this.filterExpression = filterExpression;
    }

    public boolean isInsightsEnabled() {
        return insightsEnabled;
    }

    public void setInsightsEnabled(boolean insightsEnabled) {
        this.insightsEnabled = insightsEnabled;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean notificationsEnabled) {
        this.notificationsEnabled = notificationsEnabled;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
