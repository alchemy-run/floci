package io.github.hectorvent.floci.services.ce.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost Explorer cost-anomaly subscription.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_AnomalySubscription.html">AnomalySubscription</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalySubscription {

    private String subscriptionArn;
    private String accountId;
    private List<String> monitorArnList = new ArrayList<>();
    private List<Subscriber> subscribers = new ArrayList<>();
    private Double threshold;
    private String frequency;
    private String subscriptionName;
    private JsonNode thresholdExpression;
    private Map<String, String> tags = new LinkedHashMap<>();

    public AnomalySubscription() {
    }

    public String getSubscriptionArn() {
        return subscriptionArn;
    }

    public void setSubscriptionArn(String subscriptionArn) {
        this.subscriptionArn = subscriptionArn;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public List<String> getMonitorArnList() {
        return monitorArnList;
    }

    public void setMonitorArnList(List<String> monitorArnList) {
        this.monitorArnList = monitorArnList == null ? new ArrayList<>() : monitorArnList;
    }

    public List<Subscriber> getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(List<Subscriber> subscribers) {
        this.subscribers = subscribers == null ? new ArrayList<>() : subscribers;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public String getSubscriptionName() {
        return subscriptionName;
    }

    public void setSubscriptionName(String subscriptionName) {
        this.subscriptionName = subscriptionName;
    }

    public JsonNode getThresholdExpression() {
        return thresholdExpression;
    }

    public void setThresholdExpression(JsonNode thresholdExpression) {
        this.thresholdExpression = thresholdExpression;
    }

    public Map<String, String> getTags() {
        if (tags == null) {
            tags = new LinkedHashMap<>();
        }
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : tags;
    }

    @JsonIgnore
    public Map<String, String> getResourceTags() {
        return getTags();
    }

    @JsonIgnore
    public void setResourceTags(Map<String, String> resourceTags) {
        setTags(resourceTags);
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Subscriber {
        private String address;
        private String type;
        private String status;

        public Subscriber() {
        }

        public Subscriber(String address, String type, String status) {
            this.address = address;
            this.type = type;
            this.status = status;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
