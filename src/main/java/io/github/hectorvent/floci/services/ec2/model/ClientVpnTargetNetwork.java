package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A subnet associated with a Client VPN endpoint. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientVpnTargetNetwork {

    private String associationId;
    private String vpcId;
    private String subnetId;
    private String availabilityZone;
    private String availabilityZoneId;
    private String status;
    private String clientToken;

    public ClientVpnTargetNetwork() {}

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public String getAvailabilityZoneId() { return availabilityZoneId; }
    public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
