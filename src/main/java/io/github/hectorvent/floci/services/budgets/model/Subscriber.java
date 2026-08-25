package io.github.hectorvent.floci.services.budgets.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Objects;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Subscriber {

    private String subscriptionType;
    private String address;

    public Subscriber() {
    }

    public Subscriber(String subscriptionType, String address) {
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

    public Subscriber copy() {
        return new Subscriber(subscriptionType, address);
    }

    public boolean sameAs(Subscriber other) {
        return other != null
                && Objects.equals(subscriptionType, other.subscriptionType)
                && Objects.equals(address, other.address);
    }
}
