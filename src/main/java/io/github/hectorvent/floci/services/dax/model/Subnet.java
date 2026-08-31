package io.github.hectorvent.floci.services.dax.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Subnet {

    private String subnetIdentifier;
    private String subnetAvailabilityZone;
    private List<String> supportedNetworkTypes = new ArrayList<>();

    public Subnet() {
    }

    public String getSubnetIdentifier() {
        return subnetIdentifier;
    }

    public void setSubnetIdentifier(String subnetIdentifier) {
        this.subnetIdentifier = subnetIdentifier;
    }

    public String getSubnetAvailabilityZone() {
        return subnetAvailabilityZone;
    }

    public void setSubnetAvailabilityZone(String subnetAvailabilityZone) {
        this.subnetAvailabilityZone = subnetAvailabilityZone;
    }

    public List<String> getSupportedNetworkTypes() {
        return supportedNetworkTypes;
    }

    public void setSupportedNetworkTypes(List<String> supportedNetworkTypes) {
        this.supportedNetworkTypes = supportedNetworkTypes != null ? supportedNetworkTypes : new ArrayList<>();
    }
}
