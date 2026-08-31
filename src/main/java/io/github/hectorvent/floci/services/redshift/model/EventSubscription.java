package io.github.hectorvent.floci.services.redshift.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventSubscription {

    private String customerAwsId;
    private String custSubscriptionId;
    private String snsTopicArn;
    private String status = "active";
    private Instant subscriptionCreationTime;
    private String sourceType;
    private List<String> sourceIds = new ArrayList<>();
    private List<String> eventCategories = new ArrayList<>();
    private String severity;
    private boolean enabled = true;
    private Map<String, String> tags = new LinkedHashMap<>();

    public EventSubscription() {}

    public String getCustomerAwsId() { return customerAwsId; }
    public void setCustomerAwsId(String customerAwsId) { this.customerAwsId = customerAwsId; }

    public String getCustSubscriptionId() { return custSubscriptionId; }
    public void setCustSubscriptionId(String custSubscriptionId) {
        this.custSubscriptionId = custSubscriptionId;
    }

    public String getSnsTopicArn() { return snsTopicArn; }
    public void setSnsTopicArn(String snsTopicArn) { this.snsTopicArn = snsTopicArn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getSubscriptionCreationTime() { return subscriptionCreationTime; }
    public void setSubscriptionCreationTime(Instant subscriptionCreationTime) {
        this.subscriptionCreationTime = subscriptionCreationTime;
    }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public List<String> getSourceIds() { return sourceIds; }
    public void setSourceIds(List<String> sourceIds) {
        this.sourceIds = sourceIds != null ? new ArrayList<>(sourceIds) : new ArrayList<>();
    }

    public List<String> getEventCategories() { return eventCategories; }
    public void setEventCategories(List<String> eventCategories) {
        this.eventCategories = eventCategories != null ? new ArrayList<>(eventCategories) : new ArrayList<>();
    }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>();
    }
}
