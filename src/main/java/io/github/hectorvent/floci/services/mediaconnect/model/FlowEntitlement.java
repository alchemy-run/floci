package io.github.hectorvent.floci.services.mediaconnect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Subscriber entitlement granted on a MediaConnect flow. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FlowEntitlement {

    private String name;
    private String entitlementArn;
    private String description;
    private String entitlementStatus;
    private List<String> subscribers = new ArrayList<>();
    private Integer dataTransferSubscriberFeePercent;

    public FlowEntitlement() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEntitlementArn() {
        return entitlementArn;
    }

    public void setEntitlementArn(String entitlementArn) {
        this.entitlementArn = entitlementArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEntitlementStatus() {
        return entitlementStatus;
    }

    public void setEntitlementStatus(String entitlementStatus) {
        this.entitlementStatus = entitlementStatus;
    }

    public List<String> getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(List<String> subscribers) {
        this.subscribers = subscribers == null ? new ArrayList<>() : new ArrayList<>(subscribers);
    }

    public Integer getDataTransferSubscriberFeePercent() {
        return dataTransferSubscriberFeePercent;
    }

    public void setDataTransferSubscriberFeePercent(Integer dataTransferSubscriberFeePercent) {
        this.dataTransferSubscriberFeePercent = dataTransferSubscriberFeePercent;
    }
}
