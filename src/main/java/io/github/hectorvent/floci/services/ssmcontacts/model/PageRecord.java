package io.github.hectorvent.floci.services.ssmcontacts.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PageRecord {

    private String pageArn;
    private String engagementArn;
    private String contactArn;
    private String sender;
    private String subject;
    private String content;
    private String publicSubject;
    private String publicContent;
    private String incidentId;
    private long sentTime;
    private Long deliveryTime;
    private Long readTime;
    private boolean accepted;

    public PageRecord() {
    }

    public String getPageArn() {
        return pageArn;
    }

    public void setPageArn(String pageArn) {
        this.pageArn = pageArn;
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

    public long getSentTime() {
        return sentTime;
    }

    public void setSentTime(long sentTime) {
        this.sentTime = sentTime;
    }

    public Long getDeliveryTime() {
        return deliveryTime;
    }

    public void setDeliveryTime(Long deliveryTime) {
        this.deliveryTime = deliveryTime;
    }

    public Long getReadTime() {
        return readTime;
    }

    public void setReadTime(Long readTime) {
        this.readTime = readTime;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }
}
