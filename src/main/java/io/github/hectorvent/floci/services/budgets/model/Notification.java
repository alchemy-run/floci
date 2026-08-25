package io.github.hectorvent.floci.services.budgets.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Notification {

    private String notificationType;
    private String comparisonOperator;
    private double threshold;
    private String thresholdType;
    private String notificationState;
    private List<Subscriber> subscribers = new ArrayList<>();

    public Notification() {
    }

    public String getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public String getComparisonOperator() {
        return comparisonOperator;
    }

    public void setComparisonOperator(String comparisonOperator) {
        this.comparisonOperator = comparisonOperator;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public String getThresholdType() {
        return thresholdType;
    }

    public void setThresholdType(String thresholdType) {
        this.thresholdType = thresholdType;
    }

    public String resolvedThresholdType() {
        return thresholdType == null || thresholdType.isEmpty() ? "PERCENTAGE" : thresholdType;
    }

    public String getNotificationState() {
        return notificationState;
    }

    public void setNotificationState(String notificationState) {
        this.notificationState = notificationState;
    }

    public List<Subscriber> getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(List<Subscriber> subscribers) {
        this.subscribers = subscribers == null ? new ArrayList<>() : subscribers;
    }

    public boolean sameIdentity(Notification other) {
        return other != null
                && Objects.equals(notificationType, other.notificationType)
                && Objects.equals(comparisonOperator, other.comparisonOperator)
                && Double.compare(threshold, other.threshold) == 0
                && Objects.equals(resolvedThresholdType(), other.resolvedThresholdType());
    }

    public Notification copyIdentity() {
        Notification copy = new Notification();
        copy.notificationType = notificationType;
        copy.comparisonOperator = comparisonOperator;
        copy.threshold = threshold;
        copy.thresholdType = thresholdType;
        copy.notificationState = notificationState;
        return copy;
    }
}
