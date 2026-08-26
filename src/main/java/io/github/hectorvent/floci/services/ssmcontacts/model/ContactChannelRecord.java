package io.github.hectorvent.floci.services.ssmcontacts.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContactChannelRecord {

    private String contactChannelArn;
    private String contactArn;
    private String name;
    private String type;
    private JsonNode deliveryAddress;
    private String activationStatus;
    private String activationCode;

    public ContactChannelRecord() {
    }

    public String getContactChannelArn() {
        return contactChannelArn;
    }

    public void setContactChannelArn(String contactChannelArn) {
        this.contactChannelArn = contactChannelArn;
    }

    public String getContactArn() {
        return contactArn;
    }

    public void setContactArn(String contactArn) {
        this.contactArn = contactArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public JsonNode getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(JsonNode deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public String getActivationStatus() {
        return activationStatus;
    }

    public void setActivationStatus(String activationStatus) {
        this.activationStatus = activationStatus;
    }

    public String getActivationCode() {
        return activationCode;
    }

    public void setActivationCode(String activationCode) {
        this.activationCode = activationCode;
    }
}
