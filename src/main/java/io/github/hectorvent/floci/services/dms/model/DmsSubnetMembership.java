package io.github.hectorvent.floci.services.dms.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One subnet in a DMS replication subnet group.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmsSubnetMembership {

    private String subnetIdentifier;
    private String availabilityZone;
    private String subnetStatus = "Active";

    public DmsSubnetMembership() {
    }

    public DmsSubnetMembership(String subnetIdentifier, String availabilityZone) {
        this.subnetIdentifier = subnetIdentifier;
        this.availabilityZone = availabilityZone;
    }

    public String getSubnetIdentifier() {
        return subnetIdentifier;
    }

    public void setSubnetIdentifier(String subnetIdentifier) {
        this.subnetIdentifier = subnetIdentifier;
    }

    public String getAvailabilityZone() {
        return availabilityZone;
    }

    public void setAvailabilityZone(String availabilityZone) {
        this.availabilityZone = availabilityZone;
    }

    public String getSubnetStatus() {
        return subnetStatus;
    }

    public void setSubnetStatus(String subnetStatus) {
        this.subnetStatus = subnetStatus != null ? subnetStatus : "Active";
    }
}
