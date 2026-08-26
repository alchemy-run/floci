package io.github.hectorvent.floci.services.ssmcontacts.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EngagementRecord {

    private String engagementArn;
    private String contactArn;
    private String sender;
    private String subject;
    private String content;
    private String publicSubject;
    private String publicContent;
    private String incidentId;
    private long startTime;
    private Long stopTime;

    public EngagementRecord() {
    }

    public String getEngagementArn() {
        return engagementArn;
    }

    public void setEngagementArn(String engagementArn) {
        this.engagementArn = engagementArn;
    }

    public String getContactArn() {
        return contactArn;
    }

    public void setContactArn(String contactArn) {
        this.contactArn = contactArn;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getPublicSubject() {
        return publicSubject;
    }

    public void setPublicSubject(String publicSubject) {
        this.publicSubject = publicSubject;
    }

    public String getPublicContent() {
        return publicContent;
    }

    public void setPublicContent(String publicContent) {
        this.publicContent = publicContent;
    }

    public String getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(String incidentId) {
        this.incidentId = incidentId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public Long getStopTime() {
        return stopTime;
    }

    public void setStopTime(Long stopTime) {
        this.stopTime = stopTime;
    }
}
