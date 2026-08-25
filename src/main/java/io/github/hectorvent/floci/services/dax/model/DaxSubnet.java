package io.github.hectorvent.floci.services.dax.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DaxSubnet {

    private String subnetIdentifier;
    private String availabilityZone;

    public DaxSubnet() {}

    public DaxSubnet(String subnetIdentifier, String availabilityZone) {
        this.subnetIdentifier = subnetIdentifier;
        this.availabilityZone = availabilityZone;
    }

    public String getSubnetIdentifier() { return subnetIdentifier; }
    public void setSubnetIdentifier(String subnetIdentifier) { this.subnetIdentifier = subnetIdentifier; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
}
