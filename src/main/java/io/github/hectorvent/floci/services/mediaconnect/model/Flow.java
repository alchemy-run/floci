package io.github.hectorvent.floci.services.mediaconnect.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An AWS Elemental MediaConnect flow. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Flow {

    private String flowId;
    private String flowArn;
    private String name;
    private String status;
    private String availabilityZone;
    private String description;
    private String egressIp;
    private String region;
    private FlowSource source;
    private List<FlowOutput> outputs = new ArrayList<>();
    private List<FlowEntitlement> entitlements = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public Flow() {
    }

    public String getFlowId() {
        return flowId;
    }

    public void setFlowId(String flowId) {
        this.flowId = flowId;
    }

    public String getFlowArn() {
        return flowArn;
    }

    public void setFlowArn(String flowArn) {
        this.flowArn = flowArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAvailabilityZone() {
        return availabilityZone;
    }

    public void setAvailabilityZone(String availabilityZone) {
        this.availabilityZone = availabilityZone;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEgressIp() {
        return egressIp;
    }

    public void setEgressIp(String egressIp) {
        this.egressIp = egressIp;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public FlowSource getSource() {
        return source;
    }

    public void setSource(FlowSource source) {
        this.source = source;
    }

    public List<FlowOutput> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<FlowOutput> outputs) {
        this.outputs = outputs == null ? new ArrayList<>() : new ArrayList<>(outputs);
    }

    public List<FlowEntitlement> getEntitlements() {
        return entitlements;
    }

    public void setEntitlements(List<FlowEntitlement> entitlements) {
        this.entitlements = entitlements == null ? new ArrayList<>() : new ArrayList<>(entitlements);
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tags);
    }
}
