package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A route in a Client VPN endpoint's route table. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientVpnRoute {

    private String destinationCidr;
    private String targetSubnet;
    private String type;
    /** {@code associate} for routes added by a target network association, {@code add-route} otherwise. */
    private String origin;
    private String description;
    private String status;
    private String clientToken;

    public ClientVpnRoute() {}

    public String getDestinationCidr() { return destinationCidr; }
    public void setDestinationCidr(String destinationCidr) { this.destinationCidr = destinationCidr; }

    public String getTargetSubnet() { return targetSubnet; }
    public void setTargetSubnet(String targetSubnet) { this.targetSubnet = targetSubnet; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
