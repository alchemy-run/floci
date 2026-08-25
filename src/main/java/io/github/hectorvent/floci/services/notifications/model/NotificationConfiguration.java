package io.github.hectorvent.floci.services.notifications.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS User Notifications notification configuration. */
@RegisterForReflection
public class NotificationConfiguration {

    private String id;
    private String arn;
    private String accountId;
    private String name;
    private String description;
    private String aggregationDuration;
    private String creationTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private List<String> channels = new ArrayList<>();

    public NotificationConfiguration() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAggregationDuration() {
        return aggregationDuration;
    }

    public void setAggregationDuration(String aggregationDuration) {
        this.aggregationDuration = aggregationDuration;
    }

    public String getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(String creationTime) {
        this.creationTime = creationTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }

    public List<String> getChannels() {
        return channels;
    }

    public void setChannels(List<String> channels) {
        this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels);
    }
}
