package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class EndpointGroup {

    private String endpointGroupArn;
    private String listenerArn;
    private String endpointGroupRegion;
    private double trafficDialPercentage = 100.0;
    private Integer healthCheckPort;
    private String healthCheckProtocol;
    private String healthCheckPath;
    private int healthCheckIntervalSeconds = 30;
    private int thresholdCount = 3;
    private List<PortOverride> portOverrides = new ArrayList<>();
    private List<EndpointDescription> endpoints = new ArrayList<>();
    private String idempotencyToken;

    public EndpointGroup() {
    }

    public String getEndpointGroupArn() {
        return endpointGroupArn;
    }

    public void setEndpointGroupArn(String endpointGroupArn) {
        this.endpointGroupArn = endpointGroupArn;
    }

    public String getListenerArn() {
        return listenerArn;
    }

    public void setListenerArn(String listenerArn) {
        this.listenerArn = listenerArn;
    }

    public String getEndpointGroupRegion() {
        return endpointGroupRegion;
    }

    public void setEndpointGroupRegion(String endpointGroupRegion) {
        this.endpointGroupRegion = endpointGroupRegion;
    }

    public double getTrafficDialPercentage() {
        return trafficDialPercentage;
    }

    public void setTrafficDialPercentage(double trafficDialPercentage) {
        this.trafficDialPercentage = trafficDialPercentage;
    }

    public Integer getHealthCheckPort() {
        return healthCheckPort;
    }

    public void setHealthCheckPort(Integer healthCheckPort) {
        this.healthCheckPort = healthCheckPort;
    }

    public String getHealthCheckProtocol() {
        return healthCheckProtocol;
    }

    public void setHealthCheckProtocol(String healthCheckProtocol) {
        this.healthCheckProtocol = healthCheckProtocol;
    }

    public String getHealthCheckPath() {
        return healthCheckPath;
    }

    public void setHealthCheckPath(String healthCheckPath) {
        this.healthCheckPath = healthCheckPath;
    }

    public int getHealthCheckIntervalSeconds() {
        return healthCheckIntervalSeconds;
    }

    public void setHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) {
        this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
    }

    public int getThresholdCount() {
        return thresholdCount;
    }

    public void setThresholdCount(int thresholdCount) {
        this.thresholdCount = thresholdCount;
    }

    public List<PortOverride> getPortOverrides() {
        return portOverrides;
    }

    public void setPortOverrides(List<PortOverride> portOverrides) {
        this.portOverrides = portOverrides != null ? portOverrides : new ArrayList<>();
    }

    public List<EndpointDescription> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<EndpointDescription> endpoints) {
        this.endpoints = endpoints != null ? endpoints : new ArrayList<>();
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public void setIdempotencyToken(String idempotencyToken) {
        this.idempotencyToken = idempotencyToken;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PortOverride {
        private Integer listenerPort;
        private Integer endpointPort;

        public PortOverride() {
        }

        public Integer getListenerPort() {
            return listenerPort;
        }

        public void setListenerPort(Integer listenerPort) {
            this.listenerPort = listenerPort;
        }

        public Integer getEndpointPort() {
            return endpointPort;
        }

        public void setEndpointPort(Integer endpointPort) {
            this.endpointPort = endpointPort;
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointDescription {
        private String endpointId;
        private Integer weight;
        private String healthState;
        private Boolean clientIPPreservationEnabled;
        private String attachmentArn;

        public EndpointDescription() {
        }

        public String getEndpointId() {
            return endpointId;
        }

        public void setEndpointId(String endpointId) {
            this.endpointId = endpointId;
        }

        public Integer getWeight() {
            return weight;
        }

        public void setWeight(Integer weight) {
            this.weight = weight;
        }

        public String getHealthState() {
            return healthState;
        }

        public void setHealthState(String healthState) {
            this.healthState = healthState;
        }

        public Boolean getClientIPPreservationEnabled() {
            return clientIPPreservationEnabled;
        }

        public void setClientIPPreservationEnabled(Boolean clientIPPreservationEnabled) {
            this.clientIPPreservationEnabled = clientIPPreservationEnabled;
        }

        public String getAttachmentArn() {
            return attachmentArn;
        }

        public void setAttachmentArn(String attachmentArn) {
            this.attachmentArn = attachmentArn;
        }
    }
}
