package io.github.hectorvent.floci.services.devopsguru.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A DevOps Guru notification channel bound to one SNS topic. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationChannel {

    private String id;
    private String topicArn;
    private List<String> severities;
    private List<String> messageTypes;

    public NotificationChannel() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTopicArn() {
        return topicArn;
    }

    public void setTopicArn(String topicArn) {
        this.topicArn = topicArn;
    }

    public List<String> getSeverities() {
        return severities;
    }

    public void setSeverities(List<String> severities) {
        this.severities = severities == null ? null : new ArrayList<>(severities);
    }

    public List<String> getMessageTypes() {
        return messageTypes;
    }

    public void setMessageTypes(List<String> messageTypes) {
        this.messageTypes = messageTypes == null ? null : new ArrayList<>(messageTypes);
    }
}
