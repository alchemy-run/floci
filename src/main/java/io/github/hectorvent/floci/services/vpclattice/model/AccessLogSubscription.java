package io.github.hectorvent.floci.services.vpclattice.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A VPC Lattice access log subscription. Wire names are camelCase restJson1. */
@RegisterForReflection
public class AccessLogSubscription {

    private String id;
    private String arn;
    private String resourceId;
    private String resourceArn;
    private String destinationArn;
    private String serviceNetworkLogType;
    private String createdAt;
    private String lastUpdatedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public AccessLogSubscription() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getDestinationArn() {
        return destinationArn;
    }

    public void setDestinationArn(String destinationArn) {
        this.destinationArn = destinationArn;
    }

    public String getServiceNetworkLogType() {
        return serviceNetworkLogType;
    }

    public void setServiceNetworkLogType(String serviceNetworkLogType) {
        this.serviceNetworkLogType = serviceNetworkLogType;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
