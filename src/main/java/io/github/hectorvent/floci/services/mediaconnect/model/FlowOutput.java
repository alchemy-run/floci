package io.github.hectorvent.floci.services.mediaconnect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** Egress output attached to a MediaConnect flow. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FlowOutput {

    private String name;
    private String outputArn;
    private String description;
    private String destination;
    private Integer port;
    private String protocol;
    private String listenerAddress;
    private String entitlementArn;
    private List<String> cidrAllowList = new ArrayList<>();
    private Integer maxLatency;
    private Integer minLatency;
    private Integer smoothingLatency;
    private String streamId;
    private String remoteId;

    public FlowOutput() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOutputArn() {
        return outputArn;
    }

    public void setOutputArn(String outputArn) {
        this.outputArn = outputArn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getListenerAddress() {
        return listenerAddress;
    }

    public void setListenerAddress(String listenerAddress) {
        this.listenerAddress = listenerAddress;
    }

    public String getEntitlementArn() {
        return entitlementArn;
    }

    public void setEntitlementArn(String entitlementArn) {
        this.entitlementArn = entitlementArn;
    }

    public List<String> getCidrAllowList() {
        return cidrAllowList;
    }

    public void setCidrAllowList(List<String> cidrAllowList) {
        this.cidrAllowList = cidrAllowList == null ? new ArrayList<>() : new ArrayList<>(cidrAllowList);
    }

    public Integer getMaxLatency() {
        return maxLatency;
    }

    public void setMaxLatency(Integer maxLatency) {
        this.maxLatency = maxLatency;
    }

    public Integer getMinLatency() {
        return minLatency;
    }

    public void setMinLatency(Integer minLatency) {
        this.minLatency = minLatency;
    }

    public Integer getSmoothingLatency() {
        return smoothingLatency;
    }

    public void setSmoothingLatency(Integer smoothingLatency) {
        this.smoothingLatency = smoothingLatency;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public String getRemoteId() {
        return remoteId;
    }

    public void setRemoteId(String remoteId) {
        this.remoteId = remoteId;
    }
}
