package io.github.hectorvent.floci.services.shield.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Account-level Shield Advanced subscription.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ShieldSubscription {

    private String subscriptionArn;
    private String autoRenew;
    private Long startTime;
    private Long endTime;
    private Long timeCommitmentInSeconds;
    private String proactiveEngagementStatus;

    public ShieldSubscription() {
    }

    public String getSubscriptionArn() {
        return subscriptionArn;
    }

    public void setSubscriptionArn(String subscriptionArn) {
        this.subscriptionArn = subscriptionArn;
    }

    public String getAutoRenew() {
        return autoRenew;
    }

    public void setAutoRenew(String autoRenew) {
        this.autoRenew = autoRenew;
    }

    public Long getStartTime() {
        return startTime;
    }

    public void setStartTime(Long startTime) {
        this.startTime = startTime;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
    }

    public Long getTimeCommitmentInSeconds() {
        return timeCommitmentInSeconds;
    }

    public void setTimeCommitmentInSeconds(Long timeCommitmentInSeconds) {
        this.timeCommitmentInSeconds = timeCommitmentInSeconds;
    }

    public String getProactiveEngagementStatus() {
        return proactiveEngagementStatus;
    }

    public void setProactiveEngagementStatus(String proactiveEngagementStatus) {
        this.proactiveEngagementStatus = proactiveEngagementStatus;
    }
}
