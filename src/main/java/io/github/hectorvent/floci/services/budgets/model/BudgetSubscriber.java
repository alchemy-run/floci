package io.github.hectorvent.floci.services.budgets.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetSubscriber {

    private String subscriptionType;
    private String address;

    public BudgetSubscriber() {
    }

    public BudgetSubscriber(String subscriptionType, String address) {
        this.subscriptionType = subscriptionType;
        this.address = address;
    }

    public String getSubscriptionType() {
        return subscriptionType;
    }

    public void setSubscriptionType(String subscriptionType) {
        this.subscriptionType = subscriptionType;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }
}
