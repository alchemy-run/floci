package io.github.hectorvent.floci.services.iotmanagedintegrations.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An IoT Managed Integrations notification destination. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Destination {

    private String name;
    private String deliveryDestinationArn;
    private String deliveryDestinationType;
    private String roleArn;
    private String description;
    private String clientToken;
    private long createdAt;
    private long updatedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Destination() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDeliveryDestinationArn() {
        return deliveryDestinationArn;
    }

    public void setDeliveryDestinationArn(String deliveryDestinationArn) {
        this.deliveryDestinationArn = deliveryDestinationArn;
    }

    public String getDeliveryDestinationType() {
        return deliveryDestinationType;
    }

    public void setDeliveryDestinationType(String deliveryDestinationType) {
        this.deliveryDestinationType = deliveryDestinationType;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
