package io.github.hectorvent.floci.services.networkfirewall.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkFirewallFlowOperation {

    private String flowOperationId;
    private String firewallArn;
    private String flowOperationType;
    private String flowOperationStatus;
    private long flowRequestTimestamp;
    private String availabilityZone;
    private Integer minimumFlowAgeInSeconds;
    private JsonNode flowFilters;

    public NetworkFirewallFlowOperation() {
    }

    public String getFlowOperationId() {
        return flowOperationId;
    }

    public void setFlowOperationId(String flowOperationId) {
        this.flowOperationId = flowOperationId;
    }

    public String getFirewallArn() {
        return firewallArn;
    }

    public void setFirewallArn(String firewallArn) {
        this.firewallArn = firewallArn;
    }

    public String getFlowOperationType() {
        return flowOperationType;
    }

    public void setFlowOperationType(String flowOperationType) {
        this.flowOperationType = flowOperationType;
    }

    public String getFlowOperationStatus() {
        return flowOperationStatus;
    }

    public void setFlowOperationStatus(String flowOperationStatus) {
        this.flowOperationStatus = flowOperationStatus;
    }

    public long getFlowRequestTimestamp() {
        return flowRequestTimestamp;
    }

    public void setFlowRequestTimestamp(long flowRequestTimestamp) {
        this.flowRequestTimestamp = flowRequestTimestamp;
    }

    public String getAvailabilityZone() {
        return availabilityZone;
    }

    public void setAvailabilityZone(String availabilityZone) {
        this.availabilityZone = availabilityZone;
    }

    public Integer getMinimumFlowAgeInSeconds() {
        return minimumFlowAgeInSeconds;
    }

    public void setMinimumFlowAgeInSeconds(Integer minimumFlowAgeInSeconds) {
        this.minimumFlowAgeInSeconds = minimumFlowAgeInSeconds;
    }

    public JsonNode getFlowFilters() {
        return flowFilters;
    }

    public void setFlowFilters(JsonNode flowFilters) {
        this.flowFilters = flowFilters;
    }
}
