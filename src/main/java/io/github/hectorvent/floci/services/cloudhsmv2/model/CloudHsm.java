package io.github.hectorvent.floci.services.cloudhsmv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CloudHsm {

    private String hsmId;
    private String clusterId;
    private String availabilityZone;
    private String subnetId;
    private String eniId;
    private String eniIp;
    private String hsmType;
    private String state;
    private String stateMessage;

    public CloudHsm() {
    }

    public String getHsmId() {
        return hsmId;
    }

    public void setHsmId(String hsmId) {
        this.hsmId = hsmId;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getAvailabilityZone() {
        return availabilityZone;
    }

    public void setAvailabilityZone(String availabilityZone) {
        this.availabilityZone = availabilityZone;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }

    public String getEniId() {
        return eniId;
    }

    public void setEniId(String eniId) {
        this.eniId = eniId;
    }

    public String getEniIp() {
        return eniIp;
    }

    public void setEniIp(String eniIp) {
        this.eniIp = eniIp;
    }

    public String getHsmType() {
        return hsmType;
    }

    public void setHsmType(String hsmType) {
        this.hsmType = hsmType;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateMessage() {
        return stateMessage;
    }

    public void setStateMessage(String stateMessage) {
        this.stateMessage = stateMessage;
    }
}
